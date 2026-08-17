package nu.miguel.personabackend.graph;

import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.personabackend.document.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import static nu.miguel.personabackend.graph.EditorGraphProjection.*;

/**
 * Compiles browser graph gestures into bounded source-range edits. The browser never submits a
 * replacement graph or serialized YAML document, and every operation is reparsed before the next
 * operation in a compound gesture is considered.
 */
@Service
public final class GraphMutationService {
    static final int MAX_OPERATIONS = 64;
    private static final int MAX_VALUE_BYTES = 65_536;
    private static final Set<String> KINDS = Set.of("behavior", "dialogue", "quest", "npc", "script");
    private static final Set<String> BEHAVIOR_CONTAINERS = Set.of(
            "sequence", "selector", "priority-selector", "parallel");
    private static final Set<String> BEHAVIOR_WRAPPERS = Set.of(
            "sequence", "selector", "priority-selector", "parallel", "invert", "repeat", "retry",
            "timeout", "cooldown", "checkpoint");
    private static final Set<String> SCRIPT_NODES = Set.of("say", "script-say", "wait", "script-wait",
            "if", "script-if", "choice", "script-choice", "random", "script-random", "run-script",
            "script-run-script", "goto", "script-goto", "end-dialogue", "script-end-dialogue",
            "stop", "terminal", "script-stop", "extension-command");
    private final YamlDocumentService documents;
    private final GraphProjectionService projections;

    public GraphMutationService(YamlDocumentService documents, GraphProjectionService projections) {
        this.documents = documents;
        this.projections = projections;
    }

    public GraphMutationResponse mutate(GraphMutationRequest request) {
        requireRequest(request);
        GraphRequestBounds.requireProjectFiles(request.projectFiles(), request.path(), request.yamlPath());
        String previousDigest = sha256(request.content());
        if (!constantEquals(previousDigest, request.expectedDigest()))
            throw error(HttpStatus.CONFLICT, "STALE_CONTENT",
                    "The document changed before this graph gesture was applied", request.path(), request.yamlPath());

        String content = request.content();
        LinkedHashSet<String> affected = new LinkedHashSet<>();
        try {
            for (GraphMutationOperation operation : request.operations()) {
                EditorGraphProjection graph = project(request, content);
                content = apply(request, graph, content, operation, affected).content();
            }
        } catch (GraphContractException error) {
            throw error;
        } catch (ResponseStatusException error) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "UNSAFE_YAML_PATCH",
                    error.getReason() == null ? "The graph gesture cannot be represented safely" : error.getReason(),
                    request.path(), affected.isEmpty() ? request.yamlPath() : affected.getLast());
        } catch (IllegalArgumentException error) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_NODE_KIND",
                    error.getMessage() == null ? "Unsupported graph node kind" : error.getMessage(),
                    request.path(), affected.isEmpty() ? request.yamlPath() : affected.getLast());
        }

        YamlDocumentResponse document = documents.parse(content);
        if (!document.valid()) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MUTATION_RESULT",
                "The graph gesture did not produce valid YAML", request.path(), request.yamlPath());
        String digest = sha256(content);
        EditorGraphProjection projection = project(request, content);
        return new GraphMutationResponse(previousDigest, digest, content, document, projection,
                List.copyOf(affected), request.operations().size());
    }

    private YamlDocumentResponse apply(GraphMutationRequest request, EditorGraphProjection graph, String content,
                                       GraphMutationOperation operation, Set<String> affected) {
        if (operation == null || operation.type() == null)
            throw error(HttpStatus.BAD_REQUEST, "INVALID_OPERATION", "Graph operation type is required",
                    request.path(), request.yamlPath());
        validateText(operation.value(), "value", request, operation);
        validateText(operation.key(), "key", request, operation);
        validateText(operation.sourceFilePath(), "source file path", request, operation);
        return switch (operation.type()) {
            case EDIT_FIELD -> {
                requirePath(operation.yamlPath(), request, operation);
                boolean startField = request.resourceKind().equals("dialogue") && operation.yamlPath().equals("/start");
                if (!startField && graph.nodes().stream().flatMap(node -> node.fields().stream())
                        .noneMatch(field -> field.yamlPath().equals(operation.yamlPath()) && field.editable() && !field.custom()))
                    throw error(HttpStatus.UNPROCESSABLE_ENTITY, "FIELD_NOT_EDITABLE",
                            "The selected field is not exposed as an editable graph field",
                            request.path(), operation.yamlPath());
                affected.add(operation.yamlPath());
                yield documents.edit(new YamlEditRequest(content, operation.yamlPath(), operation.value()));
            }
            case DELETE -> {
                requirePath(operation.yamlPath(), request, operation);
                requireEditableNode(graph, operation.yamlPath(), request, operation);
                affected.add(operation.yamlPath());
                yield documents.structure(new YamlStructureRequest(content,
                        YamlStructureRequest.Operation.DELETE, operation.yamlPath(), null));
            }
            case DUPLICATE -> {
                requirePath(operation.yamlPath(), request, operation);
                requireEditableNode(graph, operation.yamlPath(), request, operation);
                affected.add(operation.yamlPath());
                yield documents.structure(new YamlStructureRequest(content,
                        YamlStructureRequest.Operation.DUPLICATE_AFTER, operation.yamlPath(), null));
            }
            case COPY -> {
                if (!"behavior".equals(request.resourceKind()))
                    throw unsupported(request, operation, "Cross-resource visual copy requires compatible behavior graphs");
                requirePath(operation.yamlPath(), request, operation);
                requirePath(operation.parentYamlPath(), request, operation);
                requireSimpleKey(operation.key(), request, operation);
                ContentFile source = request.projectFiles().stream()
                        .filter(file -> Objects.equals(file.path(), operation.sourceFilePath())).findFirst()
                        .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "COPY_SOURCE_NOT_FOUND",
                                "The copied source document is no longer in the project context",
                                request.path(), operation.yamlPath()));
                if (!source.path().startsWith("behaviors/"))
                    throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INCOMPATIBLE_COPY_SOURCE",
                            "Only behavior nodes can be copied into this graph", request.path(), operation.yamlPath());
                YamlDocumentResponse sourceDocument = documents.parse(source.content());
                YamlDocumentNode sourceNode = find(sourceDocument.root(), operation.yamlPath());
                if (sourceNode == null || !"mapping".equals(sourceNode.kind())
                        || find(sourceNode, operation.yamlPath() + "/type") == null)
                    throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_COPY_SOURCE",
                            "The copied YAML path is not a complete behavior node", source.path(), operation.yamlPath());
                requireInsertDestination(graph, request, operation, operation.parentYamlPath(), "action");
                affected.add(operation.parentYamlPath());
                yield documents.copySequenceItem(content, operation.parentYamlPath(), source.content(),
                        operation.yamlPath(), operation.index(), operation.key());
            }
            case REORDER -> {
                requirePath(operation.yamlPath(), request, operation);
                requirePath(operation.parentYamlPath(), request, operation);
                requireEditableNode(graph, operation.yamlPath(), request, operation);
                requireInsertDestination(graph, request, operation, operation.parentYamlPath(), null);
                affected.add(operation.yamlPath()); affected.add(operation.parentYamlPath());
                yield documents.moveSequenceItem(content, operation.yamlPath(), operation.parentYamlPath(), operation.index());
            }
            case INSERT -> {
                requireInsertDestination(graph, request, operation, operation.parentYamlPath(), operation.nodeKind());
                yield insert(request, content, operation, affected);
            }
            case WRAP -> {
                if (!"behavior".equals(request.resourceKind())) unsupported(request, operation, "Wrap is only valid in behaviour graphs");
                requirePath(operation.yamlPath(), request, operation);
                requireEditableNode(graph, operation.yamlPath(), request, operation);
                requireSimpleKey(operation.key(), request, operation);
                if (!BEHAVIOR_WRAPPERS.contains(operation.nodeKind()))
                    throw error(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_NODE_KIND",
                            "Unsupported behaviour wrapper kind", request.path(), operation.yamlPath());
                affected.add(operation.yamlPath());
                yield documents.wrapSequenceItem(content, operation.yamlPath(), operation.key(), operation.nodeKind());
            }
            case UNWRAP -> {
                if (!"behavior".equals(request.resourceKind())) unsupported(request, operation, "Unwrap is only valid in behaviour graphs");
                requirePath(operation.yamlPath(), request, operation);
                GraphNode wrapper = requireEditableNode(graph, operation.yamlPath(), request, operation);
                if (!BEHAVIOR_WRAPPERS.contains(wrapper.kind()))
                    throw error(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_NODE_KIND",
                            "Only a supported behaviour wrapper can be unwrapped", request.path(), operation.yamlPath());
                affected.add(operation.yamlPath());
                yield documents.unwrapSequenceItem(content, operation.yamlPath());
            }
            case CONNECT -> connect(request, graph, content, operation, affected);
            case DISCONNECT -> disconnect(request, graph, content, operation, affected);
        };
    }

    private YamlDocumentResponse insert(GraphMutationRequest request, String content,
                                        GraphMutationOperation operation, Set<String> affected) {
        requirePath(operation.parentYamlPath(), request, operation);
        String kind = operation.nodeKind();
        if (kind == null) unsupported(request, operation, "A node kind is required");
        affected.add(operation.parentYamlPath());
        if ("dialogue-entry".equals(kind)) {
            requireSimpleKey(operation.key(), request, operation);
            return documents.insertField(new YamlMappingInsertRequest(content, operation.parentYamlPath(),
                    operation.key(), "script:\n  - type: say\n    text: \"New line\""));
        }
        if ("npc-anchor".equals(kind)) {
            requireSimpleKey(operation.key(), request, operation);
            if (find(documents.parse(content).root(), operation.parentYamlPath()) == null
                    && operation.parentYamlPath().equals(normalizeRoot(request.yamlPath()) + "/anchors"))
                return documents.insertField(new YamlMappingInsertRequest(content, normalizeRoot(request.yamlPath()),
                        "anchors", operation.key() + ":\n  world: world\n  x: 0\n  y: 64\n  z: 0"));
            return documents.insertField(new YamlMappingInsertRequest(content, operation.parentYamlPath(),
                    operation.key(), "world: world\nx: 0\ny: 64\nz: 0"));
        }
        String yaml = template(request.resourceKind(), kind, operation.key(), operation.value());
        if (SCRIPT_NODES.contains(kind) && find(documents.parse(content).root(), operation.parentYamlPath()) == null) {
            String ownerPath = parentPath(operation.parentYamlPath());
            String hook = operation.parentYamlPath().substring(operation.parentYamlPath().lastIndexOf('/') + 1);
            if (Set.of("on-start", "on-complete", "on-fail", "on-reset", "on-interact", "on-no-dialogue").contains(hook)
                    && find(documents.parse(content).root(), ownerPath) != null)
                return documents.insertField(new YamlMappingInsertRequest(content, ownerPath, hook, yaml));
        }
        return documents.insertSequenceItem(content, operation.parentYamlPath(), yaml, operation.index());
    }

    private YamlDocumentResponse connect(GraphMutationRequest request, EditorGraphProjection graph, String content,
                                         GraphMutationOperation operation, Set<String> affected) {
        GraphPin source = pin(graph, operation.sourcePinId(), "output", request, operation);
        GraphPin target = pin(graph, operation.targetPinId(), "input", request, operation);
        GraphNode sourceNode = node(graph, source.nodeId());
        GraphNode targetNode = node(graph, target.nodeId());
        if (!compatible(source.semanticType(), target.semanticType()))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PIN_TYPE",
                    "The " + source.semanticType() + " output cannot connect to a " + target.semanticType() + " input",
                    request.path(), target.yamlPath());
        if (sourceNode.id().equals(targetNode.id()) || reaches(graph, targetNode.id(), sourceNode.id()))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "CYCLE_NOT_ALLOWED",
                    "This connection would create a cycle that is not allowed here", request.path(), targetNode.yamlPath());
        long inbound = graph.edges().stream().filter(edge -> edge.targetPinId().equals(target.id())).count();
        if ("single".equals(target.cardinality()) && inbound > 0 && !"behavior".equals(request.resourceKind()))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "CARDINALITY_EXCEEDED",
                    "The target pin accepts only one connection", request.path(), targetNode.yamlPath());

        return switch (request.resourceKind()) {
            case "behavior" -> {
                if (!BEHAVIOR_CONTAINERS.contains(sourceNode.kind()))
                    throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PARENT_NODE",
                            sourceNode.kind() + " nodes cannot own ordered children", request.path(), sourceNode.yamlPath());
                String destination = sourceNode.yamlPath() + "/children";
                affected.add(targetNode.yamlPath()); affected.add(destination);
                yield documents.moveSequenceItem(content, targetNode.yamlPath(), destination, operation.index());
            }
            case "dialogue" -> {
                if (!"dialogue-entry".equals(sourceNode.kind()) || !"dialogue-entry".equals(targetNode.kind()))
                    unsupported(request, operation, "Dialogue flow connects dialogue entry nodes");
                String scriptPath = sourceNode.yamlPath() + "/script";
                affected.add(scriptPath);
                yield documents.insertSequenceItem(content, scriptPath,
                        "- type: goto\n  node: " + targetNode.title(), operation.index());
            }
            case "quest" -> connectQuest(request, content, sourceNode, targetNode, operation, affected);
            case "script" -> {
                String parent = parentPath(sourceNode.yamlPath());
                if (!parent.equals(parentPath(targetNode.yamlPath())))
                    unsupported(request, operation, "Script sequence connections cannot cross list boundaries");
                affected.add(sourceNode.yamlPath()); affected.add(targetNode.yamlPath());
                int sourceIndex = pathIndex(sourceNode.yamlPath());
                yield documents.moveSequenceItem(content, targetNode.yamlPath(), parent, sourceIndex + 1);
            }
            default -> throw unsupported(request, operation,
                    "This content graph does not expose a compatible connect mutation");
        };
    }

    private YamlDocumentResponse connectQuest(GraphMutationRequest request, String content, GraphNode sourceNode,
                                              GraphNode targetNode, GraphMutationOperation operation,
                                              Set<String> affected) {
        if (!"quest-phase".equals(sourceNode.kind()) || !"quest-phase".equals(targetNode.kind()))
            throw unsupported(request, operation, "Quest phase flow only connects phase nodes");
        String branches = sourceNode.yamlPath() + "/branches";
        YamlDocumentResponse parsed = documents.parse(content);
        boolean present = find(parsed.root(), branches) != null;
        affected.add(branches);
        if (!present) return documents.insertField(new YamlMappingInsertRequest(content, sourceNode.yamlPath(),
                "branches", "- next-phase: " + targetNode.title()));
        return documents.insertSequenceItem(content, branches, "- next-phase: " + targetNode.title(), operation.index());
    }

    private YamlDocumentResponse disconnect(GraphMutationRequest request, EditorGraphProjection graph, String content,
                                            GraphMutationOperation operation, Set<String> affected) {
        GraphEdge edge = graph.edges().stream().filter(candidate ->
                (operation.sourcePinId() == null || candidate.sourcePinId().equals(operation.sourcePinId()))
                        && (operation.targetPinId() == null || candidate.targetPinId().equals(operation.targetPinId()))
                        && (operation.yamlPath() == null || operation.yamlPath().equals(candidate.sourceYamlPath())))
                .findFirst().orElseThrow(() -> error(HttpStatus.NOT_FOUND, "EDGE_NOT_FOUND",
                        "The connection no longer exists", request.path(), operation.yamlPath()));
        if ("behavior".equals(request.resourceKind()))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ORPHAN_NOT_ALLOWED",
                    "Behaviour nodes cannot be left disconnected; reconnect or delete the branch",
                    request.path(), edge.targetYamlPath());
        if (!Set.of("dialogue", "quest").contains(request.resourceKind()))
            throw unsupported(request, operation, "This connection is implicit in YAML ordering and cannot be disconnected");
        if (edge.sourceYamlPath() == null || edge.sourceYamlPath().isBlank())
            throw unsupported(request, operation, "This implicit connection cannot be disconnected directly");
        affected.add(edge.sourceYamlPath());
        return documents.structure(new YamlStructureRequest(content, YamlStructureRequest.Operation.DELETE,
                edge.sourceYamlPath(), null));
    }

    private EditorGraphProjection project(GraphMutationRequest request, String content) {
        return projections.project(new GraphProjectionRequest(request.path(), request.resourceKind(),
                request.resourceId(), request.yamlPath(), content, sha256(content),
                currentFiles(request, content)));
    }

    private static List<ContentFile> currentFiles(GraphMutationRequest request, String content) {
        if (request.projectFiles().isEmpty()) return List.of();
        List<ContentFile> result = new ArrayList<>(request.projectFiles().size());
        boolean replaced = false;
        for (ContentFile file : request.projectFiles()) {
            if (file.path().equals(request.path())) {
                result.add(new ContentFile(file.path(), sha256(content), content)); replaced = true;
            } else result.add(file);
        }
        if (!replaced) result.add(new ContentFile(request.path(), sha256(content), content));
        return List.copyOf(result);
    }

    private static String template(String resourceKind, String nodeKind, String id, String extensionType) {
        String stableId = id == null || !id.matches("[a-z0-9][a-z0-9_.-]{0,127}")
                ? "node-" + UUID.randomUUID().toString().substring(0, 8) : id;
        if (nodeKind != null && nodeKind.startsWith("extension-")) requireExtensionType(extensionType);
        return switch (resourceKind) {
            case "behavior" -> switch (nodeKind) {
                case "sequence", "selector", "priority-selector", "parallel" ->
                        "- id: " + stableId + "\n  type: " + nodeKind + "\n  children: []";
                case "condition" -> "- id: " + stableId + "\n  type: condition\n  condition: chance\n  chance: 1.0";
                case "action" -> "- id: " + stableId + "\n  type: action\n  action: set-visible\n  visible: true";
                case "checkpoint" -> "- id: " + stableId + "\n  type: checkpoint";
                case "wait", "cooldown" -> "- id: " + stableId + "\n  type: " + nodeKind + "\n  duration: 1s";
                case "extension-action" -> "- id: " + stableId + "\n  type: action\n  action: " + extensionType;
                case "extension-condition" -> "- id: " + stableId + "\n  type: condition\n  condition: " + extensionType;
                default -> throw new IllegalArgumentException("Unsupported behaviour node kind");
            };
            case "quest" -> switch (nodeKind) {
                case "quest-phase" -> "- id: " + stableId + "\n  objectives: []";
                case "quest-objective", "wait" -> "- id: " + stableId + "\n  type: wait\n  duration: 1s";
                case "extension-objective" -> "- id: " + stableId + "\n  type: " + extensionType;
                case "script-say" -> "- type: say\n  text: \"New line\"";
                case "script-wait" -> "- type: wait\n  duration: 1s";
                case "script-if" -> "- type: if\n  condition: { type: chance, chance: 1.0 }\n  then: []";
                case "script-choice" -> "- type: choice\n  options: []";
                case "script-random" -> "- type: random\n  options:\n    - weight: 1\n      script: []";
                case "script-run-script" -> "- type: run-script\n  script: example";
                case "script-goto" -> "- type: goto\n  node: start";
                case "script-stop" -> "- type: stop";
                case "extension-command" -> "- type: " + extensionType;
                default -> throw new IllegalArgumentException("Unsupported quest node kind");
            };
            case "dialogue", "script", "npc" -> switch (nodeKind) {
                case "say", "script-say" -> "- type: say\n  text: \"New line\"";
                case "wait", "script-wait" -> "- type: wait\n  duration: 1s";
                case "if", "script-if" -> "- type: if\n  condition: { type: chance, chance: 1.0 }\n  then: []";
                case "choice", "script-choice" -> "- type: choice\n  options: []";
                case "random", "script-random" -> "- type: random\n  options:\n    - weight: 1\n      script: []";
                case "run-script", "script-run-script" -> "- type: run-script\n  script: example";
                case "goto", "script-goto" -> "- type: goto\n  node: start";
                case "end-dialogue", "script-end-dialogue" -> "- type: end-dialogue";
                case "stop", "terminal", "script-stop" -> "- type: stop";
                case "extension-command" -> "- type: " + extensionType;
                default -> throw new IllegalArgumentException("Unsupported script node kind");
            };
            default -> throw new IllegalArgumentException("Unsupported resource kind");
        };
    }

    private static void requireExtensionType(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9_.-]{0,62}:[a-z0-9][a-z0-9_.-]{0,62}"))
            throw new IllegalArgumentException("Extension node type must be a bounded namespaced ID");
    }

    private static boolean compatible(String source, String target) {
        return Objects.equals(source, target) || (source != null && target != null
                && (source.equals("reference") && target.startsWith("reference:" )
                || target.equals("reference") && source.startsWith("reference:")));
    }

    private static boolean reaches(EditorGraphProjection graph, String from, String target) {
        Map<String, String> owners = new HashMap<>();
        for (GraphNode node : graph.nodes()) for (GraphPin pin : node.pins()) owners.put(pin.id(), node.id());
        Map<String, List<String>> adjacency = new HashMap<>();
        for (GraphEdge edge : graph.edges()) {
            String source = owners.get(edge.sourcePinId()), destination = owners.get(edge.targetPinId());
            if (source != null && destination != null)
                adjacency.computeIfAbsent(source, ignored -> new ArrayList<>()).add(destination);
        }
        ArrayDeque<String> queue = new ArrayDeque<>(); Set<String> seen = new HashSet<>(); queue.add(from);
        while (!queue.isEmpty()) {
            String value = queue.remove(); if (!seen.add(value)) continue; if (value.equals(target)) return true;
            queue.addAll(adjacency.getOrDefault(value, List.of()));
        }
        return false;
    }

    private static GraphPin pin(EditorGraphProjection graph, String id, String direction,
                                GraphMutationRequest request, GraphMutationOperation operation) {
        if (id == null) throw error(HttpStatus.BAD_REQUEST, "PIN_REQUIRED", direction + " pin is required",
                request.path(), operation.yamlPath());
        return graph.nodes().stream().flatMap(node -> node.pins().stream()).filter(value -> value.id().equals(id))
                .filter(value -> value.direction().equals(direction)).findFirst()
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "PIN_NOT_FOUND",
                        "The " + direction + " pin no longer exists", request.path(), operation.yamlPath()));
    }

    private static GraphNode node(EditorGraphProjection graph, String id) {
        return graph.nodes().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
    }

    private static GraphNode requireEditableNode(EditorGraphProjection graph, String yamlPath,
                                                 GraphMutationRequest request, GraphMutationOperation operation) {
        return graph.nodes().stream().filter(node -> yamlPath.equals(node.yamlPath()) && !node.custom()
                        && node.range().endOffset() > node.range().startOffset())
                .findFirst().orElseThrow(() -> error(HttpStatus.UNPROCESSABLE_ENTITY, "NODE_NOT_EDITABLE",
                        "The YAML path is not an editable graph node", request.path(), operation.yamlPath()));
    }

    private static void requireInsertDestination(EditorGraphProjection graph, GraphMutationRequest request,
                                                 GraphMutationOperation operation, String parentPath, String nodeKind) {
        requirePath(parentPath, request, operation);
        boolean valid = switch (request.resourceKind()) {
            case "behavior" -> graph.nodes().stream().anyMatch(node -> !node.custom()
                    && BEHAVIOR_CONTAINERS.contains(node.kind()) && (node.yamlPath() + "/children").equals(parentPath));
            case "dialogue" -> "dialogue-entry".equals(nodeKind)
                    ? (normalizeRoot(request.yamlPath()) + "/nodes").replaceFirst("^//", "/").equals(parentPath)
                    : graph.nodes().stream().anyMatch(node -> node.kind().equals("dialogue-entry")
                            && parentPath.startsWith(node.yamlPath() + "/"));
            case "quest" -> "quest-phase".equals(nodeKind)
                    ? (normalizeRoot(request.yamlPath()) + "/phases").replaceFirst("^//", "/").equals(parentPath)
                    : graph.nodes().stream().anyMatch(node -> node.kind().equals("quest-phase")
                            && parentPath.startsWith(node.yamlPath() + "/"));
            case "npc" -> "npc-anchor".equals(nodeKind)
                    && (normalizeRoot(request.yamlPath()) + "/anchors").replaceFirst("^//", "/").equals(parentPath)
                    || SCRIPT_NODES.contains(nodeKind) && (Set.of(
                            (normalizeRoot(request.yamlPath()) + "/on-interact").replaceFirst("^//", "/"),
                            (normalizeRoot(request.yamlPath()) + "/on-no-dialogue").replaceFirst("^//", "/")).contains(parentPath)
                    || SCRIPT_NODES.contains(nodeKind) && graph.nodes().stream().anyMatch(node -> (node.kind().startsWith("script-")
                            || node.kind().equals("extension-command"))
                            && GraphMutationService.parentPath(node.yamlPath()).equals(parentPath)));
            case "script" -> parentPath.equals(normalizeRoot(request.yamlPath()))
                    || graph.nodes().stream().anyMatch(node -> parentPath.startsWith(node.yamlPath() + "/"));
            default -> false;
        };
        if (!valid) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INSERT_DESTINATION",
                "The destination is not an editable graph container", request.path(), parentPath);
    }

    private static String normalizeRoot(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    private static void requireRequest(GraphMutationRequest request) {
        if (request == null || request.graphVersion() != EditorGraphProjection.VERSION || request.path() == null
                || request.path().length() > 240 || request.resourceId() == null || request.resourceId().length() > 128
                || !KINDS.contains(request.resourceKind()) || request.content() == null
                || request.content().getBytes(StandardCharsets.UTF_8).length > 1_048_576
                || request.operations().isEmpty() || request.operations().size() > MAX_OPERATIONS)
            throw error(HttpStatus.BAD_REQUEST, "INVALID_MUTATION_REQUEST",
                    "Graph mutation request is invalid or exceeds its bounds",
                    request == null ? null : request.path(), request == null ? null : request.yamlPath());
    }

    private static void requirePath(String path, GraphMutationRequest request, GraphMutationOperation operation) {
        if (path == null || path.length() > 2_048 || (!path.isEmpty() && !path.startsWith("/")))
            throw error(HttpStatus.BAD_REQUEST, "INVALID_YAML_PATH", "A bounded absolute YAML path is required",
                    request.path(), operation.yamlPath());
    }

    private static void requireSimpleKey(String key, GraphMutationRequest request, GraphMutationOperation operation) {
        if (key == null || !key.matches("[a-z0-9][a-z0-9_.-]{0,127}"))
            throw error(HttpStatus.BAD_REQUEST, "INVALID_NODE_ID", "A lowercase stable node ID is required",
                    request.path(), operation.parentYamlPath());
    }

    private static void validateText(String value, String label, GraphMutationRequest request,
                                     GraphMutationOperation operation) {
        if (value != null && value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES)
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "OPERATION_VALUE_LIMIT",
                    "Graph operation " + label + " exceeds 64 KiB", request.path(), operation.yamlPath());
    }

    private static GraphContractException unsupported(GraphMutationRequest request,
                                                      GraphMutationOperation operation, String message) {
        throw error(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_MUTATION", message,
                request.path(), operation.yamlPath());
    }

    private static int pathIndex(String path) {
        try { return Integer.parseInt(path.substring(path.lastIndexOf('/') + 1)); }
        catch (RuntimeException error) { return Integer.MAX_VALUE / 2; }
    }
    private static String parentPath(String path) {
        int slash = path == null ? -1 : path.lastIndexOf('/'); return slash <= 0 ? "" : path.substring(0, slash);
    }
    private static YamlDocumentNode find(YamlDocumentNode node, String path) {
        if (node == null || path == null) return null; if (path.equals(node.path())) return node;
        for (YamlDocumentNode child : node.children()) { YamlDocumentNode found = find(child, path); if (found != null) return found; }
        return null;
    }
    private static String sha256(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean constantEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }
    private static GraphContractException error(HttpStatus status, String code, String message,
                                                String filePath, String yamlPath) {
        return new GraphContractException(status, code, message, filePath, yamlPath);
    }
}
