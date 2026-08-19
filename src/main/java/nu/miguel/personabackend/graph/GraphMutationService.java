package nu.miguel.personabackend.graph;

import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.persona.editor.protocol.ContentProjectRevision;
import nu.miguel.persona.editor.protocol.EditorSchemaDocument;
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
    private static final Set<String> SCRIPT_NODES = Set.of("say", "wait", "choice", "random", "run-script",
            "goto", "end-dialogue", "stop", "integer-to-number", "string-to-text", "to-string",
            "sequence", "branch", "switch", "gate", "do-once", "do-n", "for", "for-each", "while",
            "get-variable", "set-variable-node", "get-player-flag", "set-player-flag",
            "get-player-string", "set-player-string", "extension-command");
    private static final Set<String> EXPLICIT_SCRIPT_COMMANDS = Set.of("start-quest","finish-quest","deliver-items",
            "set-flag","set-variable","message","action-bar","title","play-sound","particle","give-item",
            "take-item","give-experience","run-command","teleport","lightning-effect","potion-effect","broadcast",
            "spawn-entity","set-block","npc-animation","npc-speak","npc-move");
    private final YamlDocumentService documents;
    private final GraphProjectionService projections;

    public GraphMutationService(YamlDocumentService documents, GraphProjectionService projections) {
        this.documents = documents;
        this.projections = projections;
    }

    public GraphMutationResponse mutate(GraphMutationRequest request) {
        return mutate(request, List.of(), "none");
    }

    public GraphMutationResponse mutate(GraphMutationRequest request, List<EditorSchemaDocument> schemas,
                                        String schemaCatalogVersion) {
        requireRequest(request);
        SignedSchemas signed = new SignedSchemas(schemas, schemaCatalogVersion);
        GraphRequestBounds.requireProjectFiles(request.projectFiles(), request.path(), request.yamlPath());
        if (request.resourceIdentity() != null && !request.resourceIdentity().equals(
                request.resourceKind() + ":" + request.resourceId()))
            throw error(HttpStatus.BAD_REQUEST, "RESOURCE_IDENTITY_MISMATCH",
                    "The mutation resource identity does not match its kind and ID",
                    request.path(), request.yamlPath());
        if (request.expectedProjectRevision() != null && !request.projectFiles().isEmpty()
                && !constantEquals(ContentProjectRevision.compute(request.projectFiles()), request.expectedProjectRevision()))
            throw new GraphContractException(HttpStatus.CONFLICT, "STALE_PROJECT_REVISION",
                    "The project revision changed before this graph gesture was applied",
                    request.path(), request.yamlPath(), null, null, null, null, true, null,
                    ContentProjectRevision.compute(request.projectFiles()));
        String previousDigest = sha256(request.content());
        if (!constantEquals(previousDigest, request.expectedDigest()))
            throw new GraphContractException(HttpStatus.CONFLICT, "STALE_PROJECTION",
                    "The document changed before this graph gesture was applied", request.path(), request.yamlPath(),
                    null, null, null, null, true, previousDigest, request.expectedProjectRevision());

        List<GraphMutationOperation> operations = flatten(request.operations());
        String content = request.content();
        TreeMap<String, String> projectContents = new TreeMap<>();
        request.projectFiles().forEach(file -> projectContents.put(file.path(), file.content()));
        if (!projectContents.isEmpty()) projectContents.put(request.path(), content);
        LinkedHashSet<String> affected = new LinkedHashSet<>();
        List<GraphMutationResponse.SourcePatch> patches = new ArrayList<>();
        Map<String, String> identityRemap = new LinkedHashMap<>();
        try {
            EditorGraphProjection expectedProjection = project(request, content, signed);
            for (GraphMutationOperation operation : operations)
                requireExpectedRange(expectedProjection, operation, request);
            for (GraphMutationOperation operation : operations) {
                EditorGraphProjection graph = project(request, content, signed);
                String before = content;
                content = apply(request, graph, content, operation, affected, signed).content();
                if (!before.equals(content)) patches.add(minimalPatch(request.path(), before, content));
                if (!projectContents.isEmpty()) {
                    projectContents.put(request.path(), content);
                    rewriteScriptParameterCallSites(request, operation, projectContents, patches);
                    content = projectContents.get(request.path());
                }
                if (operation.operationId() != null) {
                    Set<String> beforeIds = graph.nodes().stream().map(GraphNode::id)
                            .collect(java.util.stream.Collectors.toSet());
                    List<String> createdIds = project(request, content, signed).nodes().stream().map(GraphNode::id)
                            .filter(id -> !beforeIds.contains(id)).toList();
                    if (createdIds.size() == 1) identityRemap.put(operation.operationId(), createdIds.getFirst());
                }
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
        if (!projectContents.isEmpty()) validateProjectScriptGraphs(projectContents, signed);
        String digest = sha256(content);
        EditorGraphProjection projection = project(request, content, signed);
        List<ContentFile> rawFiles = projectContents.isEmpty() ? List.of() : projectContents.entrySet().stream()
                .map(entry -> new ContentFile(entry.getKey(), sha256(entry.getValue()), entry.getValue())).toList();
        String revision = rawFiles.isEmpty() ? request.expectedProjectRevision() : ContentProjectRevision.compute(rawFiles);
        Set<String> affectedResources = affectedResourceIdentities(request, operations, projectContents);
        return new GraphMutationResponse(previousDigest, digest, content, document, projection,
                List.copyOf(affected), operations.size(), rawFiles,
                List.copyOf(patches), projection.diagnostics(),
                List.copyOf(affectedResources), identityRemap, revision);
    }

    private Set<String> affectedResourceIdentities(GraphMutationRequest request,
                                                    List<GraphMutationOperation> operations,
                                                    Map<String, String> projectContents) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add(request.resourceKind() + ":" + request.resourceId());
        boolean signatureChanged = "script".equals(request.resourceKind()) && operations.stream().anyMatch(operation -> Set.of(
                GraphMutationOperation.Type.ADD_SCRIPT_PARAMETER,
                GraphMutationOperation.Type.RENAME_SCRIPT_PARAMETER,
                GraphMutationOperation.Type.DELETE_SCRIPT_PARAMETER,
                GraphMutationOperation.Type.CHANGE_SCRIPT_PARAMETER_TYPE,
                GraphMutationOperation.Type.REORDER_SCRIPT_PARAMETER).contains(operation.type()));
        if (!signatureChanged) return result;
        for (var entry : projectContents.entrySet()) {
            String kind = resourceKind(entry.getKey());
            if (kind == null) continue;
            YamlDocumentNode root = documents.parse(entry.getValue()).root();
            boolean callsEditedScript = mappingNodes(root).stream().anyMatch(node -> scalarChild(node, "type", "run-script")
                    && scalarChild(node, "script", request.resourceId()));
            YamlDocumentNode id = child(root, "id");
            if (callsEditedScript && id != null && id.value() != null && !id.value().isBlank())
                result.add(kind + ":" + id.value());
        }
        return result;
    }

    private static String resourceKind(String path) {
        if (path.startsWith("scripts/")) return "script";
        if (path.startsWith("dialogues/")) return "dialogue";
        if (path.startsWith("quests/")) return "quest";
        if (path.startsWith("npcs/")) return "npc";
        if (path.startsWith("behaviors/")) return "behavior";
        return null;
    }

    private void rewriteScriptParameterCallSites(GraphMutationRequest request, GraphMutationOperation operation,
                                                 Map<String, String> projectContents,
                                                 List<GraphMutationResponse.SourcePatch> patches) {
        if (!"script".equals(request.resourceKind()) || !Set.of(
                GraphMutationOperation.Type.RENAME_SCRIPT_PARAMETER,
                GraphMutationOperation.Type.DELETE_SCRIPT_PARAMETER,
                GraphMutationOperation.Type.CHANGE_SCRIPT_PARAMETER_TYPE).contains(operation.type())) return;
        String boundary = operation.parentYamlPath();
        if (boundary == null || !Set.of(normalizeRoot(request.yamlPath()) + "/inputs",
                normalizeRoot(request.yamlPath()) + "/outputs").contains(boundary))
            throw error(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER_BOUNDARY",
                    "Parameter parent must be this script's inputs or outputs mapping",
                    request.path(), boundary);
        boolean input = boundary.endsWith("/inputs");
        String oldName = operation.parameterName();
        String newName = operation.type() == GraphMutationOperation.Type.RENAME_SCRIPT_PARAMETER
                ? operation.newName() : null;
        for (String path : List.copyOf(projectContents.keySet())) {
            String before = projectContents.get(path);
            String updated = before;
            YamlDocumentResponse parsed = documents.parse(updated);
            List<YamlDocumentNode> calls = mappingNodes(parsed.root()).stream()
                    .filter(node -> scalarChild(node, "type", "run-script")
                            && scalarChild(node, "script", request.resourceId())).toList();
            for (YamlDocumentNode call : calls) {
                if (input) {
                    YamlDocumentNode inputs = child(call, "inputs");
                    YamlDocumentNode argument = inputs == null ? null : child(inputs, oldName);
                    if (argument == null) continue;
                    if (operation.type() == GraphMutationOperation.Type.CHANGE_SCRIPT_PARAMETER_TYPE) {
                        if (!literalCompatible(operation.valueType(), argument))
                            throw error(HttpStatus.CONFLICT, "INCOMPATIBLE_CALL_LITERAL",
                                    "Caller literal for " + oldName + " is not " + operation.valueType(),
                                    path, argument.path());
                        continue;
                    }
                    updated = newName == null
                            ? documents.structure(new YamlStructureRequest(updated,
                            YamlStructureRequest.Operation.DELETE, argument.path(), null)).content()
                            : documents.renameMappingKey(updated, argument.path(), newName).content();
                } else {
                    if (operation.type() == GraphMutationOperation.Type.CHANGE_SCRIPT_PARAMETER_TYPE) continue;
                    String descriptor = call.path().contains("/nodes/")
                            ? call.path().substring(0, call.path().indexOf("/nodes/")) : null;
                    if (descriptor == null || call.key() == null) continue;
                    String endpoint = call.key() + "." + oldName;
                    List<YamlDocumentNode> refs = scalarNodes(documents.parse(updated).root()).stream()
                            .filter(node -> node.path().startsWith(descriptor + "/connections/")
                                    && endpoint.equals(node.value())).toList();
                    refs = refs.stream().sorted(Comparator.comparingInt(YamlDocumentNode::startOffset).reversed()).toList();
                    for (YamlDocumentNode ref : refs) {
                        if (newName == null) {
                            String connection = parentPath(ref.path());
                            updated = documents.structure(new YamlStructureRequest(updated,
                                    YamlStructureRequest.Operation.DELETE, connection, null)).content();
                        } else updated = documents.edit(new YamlEditRequest(updated, ref.path(),
                                call.key() + "." + newName)).content();
                    }
                }
            }
            if (!before.equals(updated)) {
                projectContents.put(path, updated);
                if (!path.equals(request.path())) patches.add(minimalPatch(path, before, updated));
            }
        }
    }

    private void validateProjectScriptGraphs(Map<String, String> projectContents, SignedSchemas signed) {
        List<ContentFile> files = projectContents.entrySet().stream()
                .map(entry -> new ContentFile(entry.getKey(), sha256(entry.getValue()), entry.getValue())).toList();
        for (var entry : projectContents.entrySet()) {
            if (!entry.getKey().startsWith("scripts/") || !entry.getKey().matches(".*\\.ya?ml$")) continue;
            YamlDocumentNode descriptor = documents.parse(entry.getValue()).root();
            String id = child(descriptor, "id") == null ? "" : child(descriptor, "id").value();
            projections.project(new GraphProjectionRequest(entry.getKey(), "script", id,
                    "", entry.getValue(), sha256(entry.getValue()), files), signed.schemas(), signed.version());
        }
    }

    private static boolean literalCompatible(String valueType, YamlDocumentNode literal) {
        String tag = Objects.toString(literal.tag(), "");
        return switch (valueType) {
            case "player", "npc-instance", "condition", "dialogue-registration" -> false;
            case "boolean" -> tag.endsWith(":bool") || Set.of("true", "false").contains(literal.value());
            case "integer" -> tag.endsWith(":int") || literal.value().matches("-?(?:0|[1-9][0-9]*)");
            case "number" -> tag.endsWith(":int") || tag.endsWith(":float")
                    || literal.value().matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");
            case "duration" -> literal.value().matches("(?:P.*|[0-9]+(?:ms|s|m|h|d))");
            default -> true;
        };
    }

    private static List<YamlDocumentNode> mappingNodes(YamlDocumentNode root) {
        List<YamlDocumentNode> result = new ArrayList<>();
        Deque<YamlDocumentNode> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            YamlDocumentNode node = queue.remove();
            if ("mapping".equals(node.kind())) result.add(node);
            queue.addAll(node.children());
        }
        return result;
    }

    private static YamlDocumentNode child(YamlDocumentNode node, String key) {
        return node.children().stream().filter(candidate -> key.equals(candidate.key())).findFirst().orElse(null);
    }

    private static boolean scalarChild(YamlDocumentNode node, String key, String value) {
        YamlDocumentNode child = child(node, key);
        return child != null && value.equals(child.value());
    }

    private YamlDocumentResponse apply(GraphMutationRequest request, EditorGraphProjection graph, String content,
                                       GraphMutationOperation operation, Set<String> affected, SignedSchemas signed) {
        if (operation == null || operation.type() == null)
            throw error(HttpStatus.BAD_REQUEST, "INVALID_OPERATION", "Graph operation type is required",
                    request.path(), request.yamlPath());
        String capability = switch (operation.type()) {
            case EDIT_FIELD -> "EDIT_FIELDS";
            case DELETE -> "DELETE_NODE";
            case INSERT -> "CREATE_NODE";
            case CONNECT -> "CONNECT";
            case DISCONNECT -> "DISCONNECT";
            case RECONNECT -> "RECONNECT";
            case BREAK_ALL_LINKS, MOVE_LINKS -> "RECONNECT";
            case RENAME_NODE -> "CREATE_NODE";
            case CONNECT_WITH_AUTOCAST -> "CONNECT";
            case REPLACE_NODE -> "CREATE_NODE";
            case REORDER -> "REORDER";
            case COPY -> "COPY";
            case DUPLICATE -> "DUPLICATE";
            case WRAP -> "WRAP";
            case UNWRAP -> "UNWRAP";
            case SET_PIN_DEFAULT -> "EDIT_PIN_DEFAULT";
            case CREATE_VALUE_NODE -> "CREATE_VALUE_NODE";
            case REMOVE_VALUE_NODE -> "REMOVE_VALUE_NODE";
            case ADD_SCRIPT_PARAMETER,RENAME_SCRIPT_PARAMETER,REORDER_SCRIPT_PARAMETER,
                    DELETE_SCRIPT_PARAMETER,CHANGE_SCRIPT_PARAMETER_TYPE -> "EDIT_SCRIPT_SIGNATURE";
            case ADD_VARIABLE,RENAME_VARIABLE,DELETE_VARIABLE,CHANGE_VARIABLE_TYPE,PROMOTE_TO_VARIABLE -> "EDIT_VARIABLES";
            case COMPOUND, INSERT_ON_WIRE -> null;
        };
        if (capability != null && !graph.capabilities().contains(capability))
            throw error(HttpStatus.FORBIDDEN, "GRAPH_CAPABILITY_REQUIRED",
                    "The projection does not grant the " + capability + " graph capability",
                    request.path(), operation.yamlPath());
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
                GraphNode deleted = requireEditableNode(graph, operation.yamlPath(), request, operation);
                affected.add(operation.yamlPath());
                String updated = content;
                if (operation.yamlPath().contains("/nodes/") || operation.yamlPath().startsWith("/nodes/")) {
                    Set<String> pinIds = deleted.pins().stream().map(GraphPin::id).collect(java.util.stream.Collectors.toSet());
                    List<String> connections = graph.edges().stream().filter(edge -> edge.id().startsWith("graph-edge:"))
                            .filter(edge -> pinIds.contains(edge.sourcePinId()) || pinIds.contains(edge.targetPinId()))
                            .map(GraphEdge::sourceYamlPath).filter(Objects::nonNull)
                            .map(GraphMutationService::parentPath).distinct().sorted(Comparator.reverseOrder()).toList();
                    for (String connection : connections) { updated = documents.structure(new YamlStructureRequest(updated,
                            YamlStructureRequest.Operation.DELETE, connection, null)).content(); affected.add(connection); }
                }
                yield documents.structure(new YamlStructureRequest(updated,
                        YamlStructureRequest.Operation.DELETE, operation.yamlPath(), null));
            }
            case DUPLICATE -> {
                requirePath(operation.yamlPath(), request, operation);
                GraphNode duplicated = requireEditableNode(graph, operation.yamlPath(), request, operation);
                affected.add(operation.yamlPath());
                String parent = parentPath(operation.yamlPath());
                if (parent.endsWith("/nodes") || parent.equals("/nodes")) {
                    requireSimpleKey(operation.key(), request, operation);
                    if (duplicated.badges().contains("permanent") || duplicated.badges().contains("non-deletable"))
                        throw error(HttpStatus.UNPROCESSABLE_ENTITY, "PERMANENT_NODE",
                                "Permanent graph boundary nodes cannot be duplicated", request.path(), duplicated.yamlPath());
                    yield documents.copyMappingField(content, parent, content, operation.yamlPath(), operation.key());
                }
                yield documents.structure(new YamlStructureRequest(content,
                        YamlStructureRequest.Operation.DUPLICATE_AFTER, operation.yamlPath(), null));
            }
            case COPY -> {
                requirePath(operation.yamlPath(), request, operation);
                requirePath(operation.parentYamlPath(), request, operation);
                requireSimpleKey(operation.key(), request, operation);
                ContentFile source = request.projectFiles().stream()
                        .filter(file -> Objects.equals(file.path(), operation.sourceFilePath())).findFirst()
                        .orElseGet(() -> Objects.equals(operation.sourceFilePath(), request.path())
                                ? new ContentFile(request.path(), sha256(content), content) : null);
                if (source == null) throw error(HttpStatus.NOT_FOUND, "COPY_SOURCE_NOT_FOUND",
                        "The copied source document is no longer in the project context",
                        request.path(), operation.yamlPath());
                YamlDocumentResponse sourceDocument = documents.parse(source.content());
                YamlDocumentNode sourceNode = find(sourceDocument.root(), operation.yamlPath());
                if (sourceNode == null || !"mapping".equals(sourceNode.kind()) || child(sourceNode, "type") == null)
                    throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_COPY_SOURCE",
                            "The copied YAML path is not a complete typed node", source.path(), operation.yamlPath());
                affected.add(operation.parentYamlPath());
                if ("behavior".equals(request.resourceKind())) {
                    if (!source.path().startsWith("behaviors/"))
                        throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INCOMPATIBLE_COPY_SOURCE",
                                "Only behavior nodes can be copied into a behavior graph", request.path(), operation.yamlPath());
                    requireInsertDestination(graph, request, operation, operation.parentYamlPath(), "action");
                    yield documents.copySequenceItem(content, operation.parentYamlPath(), source.content(),
                            operation.yamlPath(), operation.index(), operation.key());
                }
                if (!Set.of("npc", "dialogue", "quest", "script").contains(request.resourceKind())
                        || !(parentPath(operation.yamlPath()).endsWith("/nodes")
                        || parentPath(operation.yamlPath()).equals("/nodes")))
                    throw unsupported(request, operation, "Only complete explicit-graph nodes can be copied here");
                requireInsertDestination(graph, request, operation, operation.parentYamlPath(), "script-value");
                yield documents.copyMappingField(content, operation.parentYamlPath(), source.content(),
                        operation.yamlPath(), operation.key());
            }
            case REORDER -> {
                requirePath(operation.yamlPath(), request, operation);
                requirePath(operation.parentYamlPath(), request, operation);
                requireEditableNode(graph, operation.yamlPath(), request, operation);
                requireInsertDestination(graph, request, operation, operation.parentYamlPath(), null);
                int targetIndex = reorderIndex(graph, content, operation, request);
                affected.add(operation.yamlPath()); affected.add(operation.parentYamlPath());
                yield documents.moveSequenceItem(content, operation.yamlPath(), operation.parentYamlPath(), targetIndex);
            }
            case INSERT -> {
                requireInsertDestination(graph, request, operation, operation.parentYamlPath(), operation.nodeKind());
                yield insert(request, content, operation, affected, signed);
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
            case RECONNECT -> reconnect(request, graph, content, operation, affected, signed);
            case SET_PIN_DEFAULT -> setPinDefault(request,graph,content,operation,affected);
            case CREATE_VALUE_NODE -> createValueNode(request,content,operation,affected);
            case REMOVE_VALUE_NODE -> removeValueNode(request,graph,content,operation,affected);
            case ADD_SCRIPT_PARAMETER -> addScriptParameter(request,content,operation,affected);
            case RENAME_SCRIPT_PARAMETER -> renameScriptParameter(request,content,operation,affected);
            case DELETE_SCRIPT_PARAMETER -> deleteScriptParameter(request,content,operation,affected);
            case CHANGE_SCRIPT_PARAMETER_TYPE -> changeScriptParameterType(request,graph,content,operation,affected);
            case REORDER_SCRIPT_PARAMETER -> reorderScriptParameter(request,graph,content,operation,affected);
            case BREAK_ALL_LINKS -> breakAllLinks(request,graph,content,operation,affected);
            case MOVE_LINKS -> moveLinks(request,graph,content,operation,affected);
            case REPLACE_NODE -> replaceNode(request,graph,content,operation,affected);
            case RENAME_NODE -> renameNode(request, graph, content, operation, affected);
            case CONNECT_WITH_AUTOCAST -> connectWithAutocast(request,graph,content,operation,affected);
            case ADD_VARIABLE -> addVariable(request,content,operation,affected);
            case RENAME_VARIABLE -> renameVariable(request,content,operation,affected);
            case DELETE_VARIABLE -> deleteVariable(request,content,operation,affected);
            case CHANGE_VARIABLE_TYPE -> changeVariableType(request,graph,content,operation,affected);
            case PROMOTE_TO_VARIABLE -> promoteToVariable(request,graph,content,operation,affected);
            case COMPOUND, INSERT_ON_WIRE -> throw error(HttpStatus.BAD_REQUEST, "INVALID_COMPOUND",
                    "Compound operations must contain bounded primitive children",
                    request.path(), operation.yamlPath());
        };
    }

    private YamlDocumentResponse setPinDefault(GraphMutationRequest request,EditorGraphProjection graph,String content,GraphMutationOperation operation,Set<String> affected){GraphPin pin=pin(graph,operation.targetPinId(),"input",request,operation);if(!"DATA".equals(pin.channel()))throw error(HttpStatus.UNPROCESSABLE_ENTITY,"DATA_PIN_REQUIRED","Only data inputs have inline defaults",request.path(),pin.yamlPath());String literal=yamlTypedScalar(operation.value(),pin.valueType());YamlDocumentNode candidate=child(documents.parse("value: "+literal+"\n").root(),"value");if(!literalCompatible(pin.valueType(),candidate))throw error(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_PIN_DEFAULT","The inline value is not "+pin.valueType(),request.path(),pin.yamlPath());String path=pin.yamlPath();GraphNode owner=node(graph,pin.nodeId());if(owner.kind().equals("script-output"))path=path+"/default";affected.add(path);YamlDocumentResponse parsed=documents.parse(content);if(find(parsed.root(),path)!=null)return documents.edit(new YamlEditRequest(content,path,operation.value()));String parent=parentPath(path),key=path.substring(path.lastIndexOf('/')+1);if(owner.kind().equals("script-call")&&find(parsed.root(),parent)==null)return documents.insertField(new YamlMappingInsertRequest(content,owner.yamlPath(),"inputs",key+": "+literal));return documents.insertField(new YamlMappingInsertRequest(content,parent,key,literal));}
    private YamlDocumentResponse createValueNode(GraphMutationRequest request, String content,
                                                 GraphMutationOperation operation, Set<String> affected) {
        requireSimpleKey(operation.key(), request, operation);
        requireValueType(operation.valueType(), request, operation);
        String literal = yamlTypedScalar(operation.value(), operation.valueType());
        YamlDocumentNode candidate = child(documents.parse("value: " + literal + "\n").root(), "value");
        if (!literalCompatible(operation.valueType(), candidate))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_VALUE_LITERAL",
                    "The value node literal is not " + operation.valueType(), request.path(), operation.yamlPath());
        String parent = operation.parentYamlPath() == null
                ? normalizeRoot(request.yamlPath()) + "/nodes" : operation.parentYamlPath();
        if (!parent.endsWith("/nodes") && !parent.equals("/nodes")) parent += "/nodes";
        String valueYaml = "type: value\nvalue-type: " + operation.valueType() + "\nvalue: " + literal;
        YamlDocumentNode root = documents.parse(content).root();
        if (find(root, parent) == null) {
            String descriptor = parentPath(parent);
            String owner = parentPath(descriptor);
            String hook = descriptor.substring(descriptor.lastIndexOf('/') + 1);
            if (hook.isBlank()) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "EXPLICIT_GRAPH_REQUIRED",
                    "A value node requires an explicit event or reusable graph", request.path(), descriptor);
            String body = "variables: {}\nnodes:\n  " + operation.key() + ":\n"
                    + indentYaml(valueYaml, 4) + "connections: {}";
            affected.add(descriptor);
            return documents.insertField(new YamlMappingInsertRequest(content, owner, hook, body));
        }
        affected.add(parent + "/" + operation.key());
        return documents.insertField(new YamlMappingInsertRequest(content, parent, operation.key(), valueYaml));
    }
    private YamlDocumentResponse removeValueNode(GraphMutationRequest request,EditorGraphProjection graph,String content,GraphMutationOperation operation,Set<String> affected){GraphNode node=requireEditableNode(graph,operation.yamlPath(),request,operation);if(!node.kind().equals("script-value"))throw error(HttpStatus.UNPROCESSABLE_ENTITY,"VALUE_NODE_REQUIRED","Only pure value nodes can use REMOVE_VALUE_NODE",request.path(),operation.yamlPath());if(graph.edges().stream().anyMatch(edge->node.pins().stream().anyMatch(pin->pin.id().equals(edge.sourcePinId())||pin.id().equals(edge.targetPinId()))))throw error(HttpStatus.CONFLICT,"VALUE_NODE_CONNECTED","Disconnect the value node before removing it",request.path(),operation.yamlPath());affected.add(operation.yamlPath());return documents.structure(new YamlStructureRequest(content,YamlStructureRequest.Operation.DELETE,operation.yamlPath(),null));}
    private YamlDocumentResponse addScriptParameter(GraphMutationRequest request,String content,GraphMutationOperation operation,Set<String> affected){scriptOnly(request,operation);requireSimpleKey(operation.key(),request,operation);requireValueType(operation.valueType(),request,operation);String parent=operation.parentYamlPath();if(!Set.of(normalizeRoot(request.yamlPath())+"/inputs",normalizeRoot(request.yamlPath())+"/outputs").contains(parent))throw error(HttpStatus.BAD_REQUEST,"INVALID_PARAMETER_BOUNDARY","Parameter parent must be this script's inputs or outputs mapping",request.path(),parent);if(Boolean.TRUE.equals(operation.required())&&operation.defaultValue()!=null)throw error(HttpStatus.UNPROCESSABLE_ENTITY,"REQUIRED_DEFAULT_CONFLICT","A required parameter cannot also declare a default",request.path(),parent);String literal=operation.defaultValue()==null?null:yamlTypedScalar(operation.defaultValue(),operation.valueType());if(literal!=null){YamlDocumentNode parsedLiteral=child(documents.parse("value: "+literal+"\n").root(),"value");if(!literalCompatible(operation.valueType(),parsedLiteral))throw error(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_PARAMETER_DEFAULT","The parameter default is not "+operation.valueType(),request.path(),parent);}StringBuilder yaml=new StringBuilder("type: ").append(operation.valueType());if(Boolean.TRUE.equals(operation.required()))yaml.append("\nrequired: true");if(literal!=null)yaml.append("\ndefault: ").append(literal);affected.add(parent+"/"+operation.key());return documents.insertField(new YamlMappingInsertRequest(content,parent,operation.key(),yaml.toString()));}
    private YamlDocumentResponse renameScriptParameter(GraphMutationRequest request,String content,GraphMutationOperation operation,Set<String> affected){scriptOnly(request,operation);requireSimpleKey(operation.parameterName(),request,operation);requireSimpleKey(operation.newName(),request,operation);String boundary=requireParameterBoundary(request,operation);String oldPath=boundary+"/"+operation.parameterName();String prefix=boundary.endsWith("/inputs")?"$input.":"$output.";YamlDocumentResponse renamed=documents.renameMappingKey(content,oldPath,operation.newName());String updated=renamed.content();for(YamlDocumentNode node:scalarNodes(documents.parse(updated).root()))if((prefix+operation.parameterName()).equals(node.value()))updated=documents.edit(new YamlEditRequest(updated,node.path(),prefix+operation.newName())).content();affected.add(oldPath);affected.add(boundary+"/"+operation.newName());return documents.parse(updated);}
    private YamlDocumentResponse deleteScriptParameter(GraphMutationRequest request,String content,GraphMutationOperation operation,Set<String> affected){scriptOnly(request,operation);requireSimpleKey(operation.parameterName(),request,operation);String boundary=requireParameterBoundary(request,operation),prefix=boundary.endsWith("/inputs")?"$input.":"$output.";String updated=content;List<YamlDocumentNode> refs=new ArrayList<>(scalarNodes(documents.parse(updated).root()).stream().filter(node->(prefix+operation.parameterName()).equals(node.value())).toList());refs.sort(Comparator.comparingInt(YamlDocumentNode::startOffset).reversed());for(YamlDocumentNode ref:refs){String connection=parentPath(ref.path());updated=documents.structure(new YamlStructureRequest(updated,YamlStructureRequest.Operation.DELETE,connection,null)).content();}String parameter=boundary+"/"+operation.parameterName();updated=documents.structure(new YamlStructureRequest(updated,YamlStructureRequest.Operation.DELETE,parameter,null)).content();affected.add(parameter);return documents.parse(updated);}
    private YamlDocumentResponse changeScriptParameterType(GraphMutationRequest request,EditorGraphProjection graph,String content,GraphMutationOperation operation,Set<String> affected){scriptOnly(request,operation);requireValueType(operation.valueType(),request,operation);GraphPin port=graph.ports().stream().filter(pin->pin.yamlPath().equals(operation.yamlPath())).findFirst().orElseThrow(()->error(HttpStatus.NOT_FOUND,"PARAMETER_PORT_NOT_FOUND","The parameter port no longer exists",request.path(),operation.yamlPath()));if(graph.edges().stream().anyMatch(edge->edge.sourcePinId().equals(port.id())||edge.targetPinId().equals(port.id())))throw error(HttpStatus.CONFLICT,"INCOMPATIBLE_PARAMETER_WIRES","Disconnect parameter wires before changing its nominal type",request.path(),operation.yamlPath());String path=operation.yamlPath()+"/type";affected.add(path);return documents.edit(new YamlEditRequest(content,path,operation.valueType()));}
    private YamlDocumentResponse reorderScriptParameter(GraphMutationRequest request,EditorGraphProjection graph,String content,GraphMutationOperation operation,Set<String> affected){scriptOnly(request,operation);requirePath(operation.yamlPath(),request,operation);GraphPin selected=graph.ports().stream().filter(pin->pin.yamlPath().equals(operation.yamlPath())&&"DATA".equals(pin.channel())).findFirst().orElseThrow(()->error(HttpStatus.NOT_FOUND,"PARAMETER_PORT_NOT_FOUND","The parameter port no longer exists",request.path(),operation.yamlPath()));boolean before=operation.beforePortId()!=null,after=operation.afterPortId()!=null;if(before==after)throw error(HttpStatus.BAD_REQUEST,"REORDER_NEIGHBOR_REQUIRED","Parameter reorder requires exactly one beforePortId or afterPortId",request.path(),operation.yamlPath());String neighborId=before?operation.beforePortId():operation.afterPortId();GraphPin neighbor=graph.ports().stream().filter(pin->pin.id().equals(neighborId)&&"DATA".equals(pin.channel())).findFirst().orElseThrow(()->error(HttpStatus.NOT_FOUND,"REORDER_NEIGHBOR_NOT_FOUND","The parameter reorder neighbor no longer exists",request.path(),operation.yamlPath()));if(!parentPath(selected.yamlPath()).equals(parentPath(neighbor.yamlPath())))throw error(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_PARAMETER_REORDER","Script parameters can only move within the same input or output boundary",request.path(),operation.yamlPath());affected.add(operation.yamlPath());return documents.moveMappingField(content,operation.yamlPath(),neighbor.yamlPath(),before);}
    private static List<YamlDocumentNode> scalarNodes(YamlDocumentNode root){List<YamlDocumentNode> out=new ArrayList<>();Deque<YamlDocumentNode> queue=new ArrayDeque<>();queue.add(root);while(!queue.isEmpty()){YamlDocumentNode node=queue.remove();if(node.children().isEmpty())out.add(node);else queue.addAll(node.children());}return out;}
    private static void scriptOnly(GraphMutationRequest request,GraphMutationOperation operation){if(!"script".equals(request.resourceKind()))throw unsupported(request,operation,"This operation is only valid in reusable script graphs");}
    private static String requireParameterBoundary(GraphMutationRequest request,GraphMutationOperation operation){String boundary=operation.parentYamlPath();if(boundary==null||!Set.of(normalizeRoot(request.yamlPath())+"/inputs",normalizeRoot(request.yamlPath())+"/outputs").contains(boundary))throw error(HttpStatus.BAD_REQUEST,"INVALID_PARAMETER_BOUNDARY","Parameter parent must be this script's inputs or outputs mapping",request.path(),boundary);return boundary;}
    private static void requireValueType(String type,GraphMutationRequest request,GraphMutationOperation operation){if(type==null||!type.matches("[a-z0-9][a-z0-9_.:-]{0,127}"))throw error(HttpStatus.BAD_REQUEST,"INVALID_VALUE_TYPE","A bounded nominal value type is required",request.path(),operation.yamlPath());}
    private static String yamlScalar(String value){if(value==null)return "null";if(value.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?|true|false|null|[a-z0-9_.:-]+"))return value;return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    private static String yamlTypedScalar(String value,String valueType){if(value==null)return "null";if(Set.of("boolean","integer","number","duration").contains(valueType))return yamlScalar(value);return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    private static String indentYaml(String value,int spaces){String prefix=" ".repeat(spaces);return prefix+value.stripTrailing().replace("\n","\n"+prefix)+"\n";}

    private YamlDocumentResponse reconnect(GraphMutationRequest request, EditorGraphProjection graph, String content,
                                           GraphMutationOperation operation, Set<String> affected, SignedSchemas signed) {
        if (operation.edgeId() == null)
            throw error(HttpStatus.BAD_REQUEST, "EDGE_REQUIRED", "Reconnect requires the existing edge ID",
                    request.path(), operation.yamlPath());
        GraphEdge existing = graph.edges().stream().filter(edge -> edge.id().equals(operation.edgeId())).findFirst()
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "EDGE_NOT_FOUND", "The connection no longer exists",
                        request.path(), operation.yamlPath()));
        GraphPin requestedTarget = pin(graph, operation.targetPinId(), "input", request, operation);
        GraphPin requestedSource = pin(graph, operation.sourcePinId(), "output", request, operation);
        GraphNode requestedTargetNode = node(graph, requestedTarget.nodeId());
        GraphNode requestedSourceNode = node(graph, requestedSource.nodeId());
        if (!existing.sourcePinId().equals(requestedSource.id()) && !existing.targetPinId().equals(requestedTarget.id()))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "RECONNECT_ENDPOINT_MISMATCH",
                    "Reconnect must retain one endpoint of the existing edge", request.path(), operation.yamlPath());
        if (!compatible(requestedSource, requestedTarget, graph))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PIN_TYPE",
                    "Reconnect endpoints have incompatible semantic types", request.path(), requestedTarget.yamlPath());
        if (!cycleAllowed(requestedSource, requestedTarget) && (requestedSourceNode.id().equals(requestedTargetNode.id())
                || reaches(graph, requestedTargetNode.id(), requestedSourceNode.id())))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "CYCLE_NOT_ALLOWED",
                    "This reconnection would create a cycle that is not allowed here",
                    request.path(), requestedTargetNode.yamlPath());
        long retainedInbound = graph.edges().stream().filter(edge -> edge.targetPinId().equals(requestedTarget.id())
                && !edge.id().equals(existing.id())).count();
        if (Set.of("ZERO_OR_ONE", "EXACTLY_ONE").contains(requestedTarget.cardinality()) && retainedInbound > 0)
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "CARDINALITY_EXCEEDED",
                    "The reconnect target accepts only one connection", request.path(), requestedTargetNode.yamlPath());
        if(existing.id().startsWith("graph-edge:") || "script".equals(request.resourceKind())){boolean sourceChanged=!existing.sourcePinId().equals(requestedSource.id());String endpointPath=(sourceChanged?existing.sourceYamlPath():existing.targetYamlPath());String replacement=sourceChanged?graphEndpoint(requestedSourceNode,requestedSource):graphEndpoint(requestedTargetNode,requestedTarget);affected.add(endpointPath);return documents.edit(new YamlEditRequest(content,endpointPath,replacement));}
        if ("dialogue".equals(request.resourceKind())) {
            String targetPath = existing.sourceYamlPath() + "/node";
            if (find(documents.parse(content).root(), targetPath) == null)
                throw unsupported(request, operation, "Only a local dialogue transfer can be reconnected");
            affected.add(targetPath);
            return documents.edit(new YamlEditRequest(content, targetPath, requestedTargetNode.title()));
        }
        if ("quest".equals(request.resourceKind()) && existing.sourceYamlPath() != null) {
            String targetPath = existing.sourceYamlPath() + "/next-phase";
            if (find(documents.parse(content).root(), targetPath) != null) {
                affected.add(targetPath);
                return documents.edit(new YamlEditRequest(content, targetPath, requestedTargetNode.title()));
            }
        }
        if ("behavior".equals(request.resourceKind())) {
            GraphPin source = requestedSource;
            GraphPin target = requestedTarget;
            GraphNode sourceNode = node(graph, source.nodeId());
            GraphNode targetNode = node(graph, target.nodeId());
            if (!BEHAVIOR_CONTAINERS.contains(sourceNode.kind()))
                throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PARENT_NODE",
                        sourceNode.kind() + " nodes cannot own ordered children", request.path(), sourceNode.yamlPath());
            affected.add(targetNode.yamlPath()); affected.add(sourceNode.yamlPath() + "/children");
            return documents.moveSequenceItem(content, targetNode.yamlPath(), sourceNode.yamlPath() + "/children", operation.index());
        }
        GraphMutationOperation disconnect = new GraphMutationOperation(GraphMutationOperation.Type.DISCONNECT,
                existing.sourceYamlPath(), null, null, existing.sourcePinId(), existing.targetPinId(),
                null, null, null, null, null, null, null, existing.id(), null, null, null, null, List.of());
        String without = disconnect(request, graph, content, disconnect, affected).content();
        return connect(request, project(request, without, signed), without, operation, affected);
    }

    private int reorderIndex(EditorGraphProjection graph, String content, GraphMutationOperation operation,
                             GraphMutationRequest request) {
        if (operation.parentPortId() == null)
            throw error(HttpStatus.BAD_REQUEST, "REORDER_PARENT_PORT_REQUIRED",
                    "Reorder requires the stable parent collection port", request.path(), operation.parentYamlPath());
        GraphPin parentPort = graph.ports().stream().filter(port -> port.id().equals(operation.parentPortId())
                        && "OUTPUT".equals(port.direction())).findFirst()
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "REORDER_PARENT_PORT_NOT_FOUND",
                        "The stable reorder parent port no longer exists", request.path(), operation.parentYamlPath()));
        if (!Objects.equals(parentPort.yamlPath(), operation.parentYamlPath()))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REORDER_PARENT_PORT",
                    "The stable parent port does not own the destination sequence",
                    request.path(), operation.parentYamlPath());
        boolean before = operation.beforePortId() != null;
        boolean after = operation.afterPortId() != null;
        if (before == after)
            throw error(HttpStatus.BAD_REQUEST, "REORDER_NEIGHBOR_REQUIRED",
                    "Reorder requires exactly one stable beforePortId or afterPortId",
                    request.path(), operation.yamlPath());
        String neighborPortId = before ? operation.beforePortId() : operation.afterPortId();
        GraphPin neighborPort = graph.ports().stream().filter(port -> port.id().equals(neighborPortId)).findFirst()
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "REORDER_NEIGHBOR_NOT_FOUND",
                        "The reorder neighbor port no longer exists", request.path(), operation.yamlPath()));
        GraphNode neighbor = graph.nodes().stream().filter(node -> node.id().equals(neighborPort.nodeId())).findFirst()
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "REORDER_NEIGHBOR_NOT_FOUND",
                        "The reorder neighbor node no longer exists", request.path(), operation.yamlPath()));
        YamlDocumentNode root = documents.parse(content).root();
        YamlDocumentNode parent = find(root, operation.parentYamlPath());
        if (parent == null || !"sequence".equals(parent.kind()))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REORDER_PARENT",
                    "The reorder destination is no longer an ordered sequence",
                    request.path(), operation.parentYamlPath());
        int neighborIndex = -1;
        for (int index = 0; index < parent.children().size(); index++) {
            if (Objects.equals(parent.children().get(index).path(), neighbor.yamlPath())) {
                neighborIndex = index;
                break;
            }
        }
        if (neighborIndex < 0 || Objects.equals(neighbor.yamlPath(), operation.yamlPath()))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REORDER_NEIGHBOR",
                    "The stable reorder neighbor is not a different child of the destination sequence",
                    request.path(), neighbor.yamlPath());
        return neighborIndex + (after ? 1 : 0);
    }

    private YamlDocumentResponse breakAllLinks(GraphMutationRequest request, EditorGraphProjection graph,
                                                String content, GraphMutationOperation operation,
                                                Set<String> affected) {
        String pinId = operation.sourcePinId() != null ? operation.sourcePinId() : operation.targetPinId();
        if (pinId == null) throw error(HttpStatus.BAD_REQUEST, "PIN_REQUIRED",
                "Break all links requires a pin", request.path(), operation.yamlPath());
        if (graph.ports().stream().noneMatch(pin -> pin.id().equals(pinId)))
            throw error(HttpStatus.NOT_FOUND, "PIN_NOT_FOUND", "The selected pin no longer exists",
                    request.path(), operation.yamlPath());
        List<GraphEdge> links = graph.edges().stream()
                .filter(edge -> edge.sourcePinId().equals(pinId) || edge.targetPinId().equals(pinId)).toList();
        if (links.stream().anyMatch(edge -> !edge.id().startsWith("graph-edge:")))
            throw unsupported(request, operation, "Implicit YAML links cannot be broken as a group");
        String updated = content;
        List<String> paths = links.stream().map(GraphEdge::sourceYamlPath).filter(Objects::nonNull)
                .map(GraphMutationService::parentPath).distinct().sorted(Comparator.reverseOrder()).toList();
        for (String path : paths) {
            updated = documents.structure(new YamlStructureRequest(updated,
                    YamlStructureRequest.Operation.DELETE, path, null)).content();
            affected.add(path);
        }
        return documents.parse(updated);
    }

    private YamlDocumentResponse moveLinks(GraphMutationRequest request, EditorGraphProjection graph,
                                            String content, GraphMutationOperation operation,
                                            Set<String> affected) {
        String oldPinId = operation.sourcePinId(), newPinId = operation.targetPinId();
        if (oldPinId == null || newPinId == null || oldPinId.equals(newPinId))
            throw error(HttpStatus.BAD_REQUEST, "MOVE_LINK_PINS_REQUIRED",
                    "Move links requires different source and destination pins", request.path(), operation.yamlPath());
        GraphPin oldPin = graph.ports().stream().filter(pin -> pin.id().equals(oldPinId)).findFirst()
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "PIN_NOT_FOUND", "The original pin no longer exists",
                        request.path(), operation.yamlPath()));
        GraphPin newPin = graph.ports().stream().filter(pin -> pin.id().equals(newPinId)).findFirst()
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "PIN_NOT_FOUND", "The destination pin no longer exists",
                        request.path(), operation.yamlPath()));
        if (!oldPin.direction().equals(newPin.direction()) || !oldPin.channel().equals(newPin.channel())
                || !oldPin.valueType().equals(newPin.valueType()))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INCOMPATIBLE_MOVE_LINKS",
                    "Links can only move between pins with the same direction, channel, and nominal type",
                    request.path(), newPin.yamlPath());
        List<GraphEdge> links = graph.edges().stream().filter(edge -> oldPin.direction().equals("OUTPUT")
                ? edge.sourcePinId().equals(oldPinId) : edge.targetPinId().equals(oldPinId)).toList();
        if (links.isEmpty()) return documents.parse(content);
        if (links.stream().anyMatch(edge -> !edge.id().startsWith("graph-edge:")))
            throw unsupported(request, operation, "Implicit YAML links cannot be moved as a group");
        if (oldPin.direction().equals("INPUT") && Set.of("ZERO_OR_ONE", "EXACTLY_ONE").contains(newPin.cardinality())) {
            long retained = graph.edges().stream().filter(edge -> edge.targetPinId().equals(newPinId)
                    && !links.contains(edge)).count();
            if (retained + links.size() > 1) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "CARDINALITY_EXCEEDED",
                    "The destination input accepts only one connection", request.path(), newPin.yamlPath());
        }
        GraphNode newOwner = node(graph, newPin.nodeId());
        String updated = content;
        for (GraphEdge edge : links) {
            GraphPin peer = oldPin.direction().equals("OUTPUT")
                    ? graph.ports().stream().filter(pin -> pin.id().equals(edge.targetPinId())).findFirst().orElseThrow()
                    : graph.ports().stream().filter(pin -> pin.id().equals(edge.sourcePinId())).findFirst().orElseThrow();
            GraphNode peerOwner = node(graph, peer.nodeId());
            boolean createsCycle = newOwner.id().equals(peerOwner.id())
                    || (oldPin.direction().equals("OUTPUT")
                    ? reaches(graph, peerOwner.id(), newOwner.id())
                    : reaches(graph, newOwner.id(), peerOwner.id()));
            if (!cycleAllowed(oldPin.direction().equals("OUTPUT") ? newPin : peer,
                    oldPin.direction().equals("OUTPUT") ? peer : newPin) && createsCycle)
                throw error(HttpStatus.UNPROCESSABLE_ENTITY, "CYCLE_NOT_ALLOWED",
                        "Moving these links would create a forbidden cycle", request.path(), newPin.yamlPath());
            String path = oldPin.direction().equals("OUTPUT") ? edge.sourceYamlPath() : edge.targetYamlPath();
            updated = documents.edit(new YamlEditRequest(updated, path, graphEndpoint(newOwner, newPin))).content();
            affected.add(path);
        }
        return documents.parse(updated);
    }

    private YamlDocumentResponse replaceNode(GraphMutationRequest request, EditorGraphProjection graph,
                                              String content, GraphMutationOperation operation,
                                              Set<String> affected) {
        requirePath(operation.yamlPath(), request, operation);
        GraphNode replaced = requireEditableNode(graph, operation.yamlPath(), request, operation);
        if (replaced.badges().contains("permanent") || replaced.badges().contains("non-deletable"))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "PERMANENT_NODE",
                    "Permanent graph boundary nodes cannot be replaced", request.path(), replaced.yamlPath());
        String type = operation.nodeKind();
        if (type == null) throw error(HttpStatus.BAD_REQUEST, "NODE_KIND_REQUIRED",
                "Replacement requires a node kind", request.path(), replaced.yamlPath());
        if (type.startsWith("script-")) type = type.substring("script-".length());
        if (type.startsWith("flow-")) type = type.substring("flow-".length());
        if (type.equals("value" ) || type.equals("script-value")) type = "value";
        if (!type.matches("[a-z][a-z0-9-]{0,63}") || type.contains(":"))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_NODE_KIND",
                    "Replacement requires a built-in graph node kind", request.path(), replaced.yamlPath());
        String typePath = replaced.yamlPath() + "/type";
        if (find(documents.parse(content).root(), typePath) == null)
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "NODE_TYPE_REQUIRED",
                    "The selected YAML node has no replaceable type field", request.path(), typePath);
        affected.add(replaced.yamlPath());
        return documents.edit(new YamlEditRequest(content, typePath, type));
    }

    private YamlDocumentResponse renameNode(GraphMutationRequest request, EditorGraphProjection graph,
                                            String content, GraphMutationOperation operation,
                                            Set<String> affected) {
        requirePath(operation.yamlPath(), request, operation);
        requireSimpleKey(operation.newName(), request, operation);
        GraphNode selected = requireEditableNode(graph, operation.yamlPath(), request, operation);
        String parent = parentPath(selected.yamlPath());
        if (!parent.endsWith("/nodes") && !parent.equals("/nodes"))
            throw unsupported(request, operation, "Only keyed explicit-graph nodes can be renamed");
        if (selected.badges().contains("permanent") || selected.badges().contains("non-deletable"))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "PERMANENT_NODE",
                    "Permanent graph boundary nodes cannot be renamed", request.path(), selected.yamlPath());
        YamlDocumentNode parsed = documents.parse(content).root();
        if (find(parsed, parent + "/" + operation.newName()) != null)
            throw error(HttpStatus.CONFLICT, "NODE_KEY_EXISTS", "A node with that stable key already exists",
                    request.path(), parent + "/" + operation.newName());
        String oldName = selected.title();
        String updated = documents.renameMappingKey(content, selected.yamlPath(), operation.newName()).content();
        String descriptor = parentPath(parent), connections = descriptor + "/connections";
        for (YamlDocumentNode scalar : scalarNodes(documents.parse(updated).root())) {
            if (!scalar.path().startsWith(connections + "/") || !Set.of("from", "to").contains(scalar.key())
                    || scalar.value() == null || !scalar.value().startsWith(oldName + ".")) continue;
            updated = documents.edit(new YamlEditRequest(updated, scalar.path(),
                    operation.newName() + scalar.value().substring(oldName.length()))).content();
            affected.add(scalar.path());
        }
        affected.add(selected.yamlPath()); affected.add(parent + "/" + operation.newName());
        return documents.parse(updated);
    }

    private YamlDocumentResponse connectWithAutocast(GraphMutationRequest request, EditorGraphProjection graph,
                                                      String content, GraphMutationOperation operation,
                                                      Set<String> affected) {
        GraphPin source = pin(graph, operation.sourcePinId(), "output", request, operation);
        GraphPin target = pin(graph, operation.targetPinId(), "input", request, operation);
        if (!source.channel().equals("DATA") || !target.channel().equals("DATA"))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATA_PIN_REQUIRED",
                    "Autocast is only available for data pins", request.path(), target.yamlPath());
        String converter = source.valueType().equals("integer") && target.valueType().equals("number")
                ? "integer-to-number" : source.valueType().equals("string") && target.valueType().equals("text")
                ? "string-to-text" : null;
        if (converter == null) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "NO_SAFE_AUTOCAST",
                "No lossless converter exists from " + source.valueType() + " to " + target.valueType(),
                request.path(), target.yamlPath());
        GraphNode sourceNode = node(graph, source.nodeId()), targetNode = node(graph, target.nodeId());
        String descriptor = commonDescriptor(sourceNode, targetNode);
        if (descriptor == null) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "CROSS_GRAPH_AUTOCAST",
                "Autocast endpoints must belong to the same explicit graph", request.path(), target.yamlPath());
        requireSimpleKey(operation.key(), request, operation);
        String castKey = operation.key() + "-cast";
        String nodesPath = descriptor + "/nodes", connectionsPath = descriptor + "/connections";
        YamlDocumentNode root = documents.parse(content).root();
        if (find(root, nodesPath + "/" + castKey) != null
                || find(root, connectionsPath + "/" + operation.key() + "-in") != null
                || find(root, connectionsPath + "/" + operation.key() + "-out") != null)
            throw error(HttpStatus.CONFLICT, "AUTOCAST_KEY_EXISTS",
                    "The autocast node or one of its connection keys already exists", request.path(), nodesPath);
        String updated = content;
        List<GraphEdge> occupied = graph.edges().stream().filter(edge -> edge.targetPinId().equals(target.id()))
                .filter(edge -> edge.id().startsWith("graph-edge:")).toList();
        for (String path : occupied.stream().map(GraphEdge::sourceYamlPath).map(GraphMutationService::parentPath)
                .distinct().sorted(Comparator.reverseOrder()).toList()) {
            updated = documents.structure(new YamlStructureRequest(updated,
                    YamlStructureRequest.Operation.DELETE, path, null)).content(); affected.add(path);
        }
        updated = documents.insertField(new YamlMappingInsertRequest(updated, nodesPath, castKey,
                "type: " + converter)).content();
        updated = documents.insertField(new YamlMappingInsertRequest(updated, connectionsPath,
                operation.key() + "-in", "from: " + graphEndpoint(sourceNode, source)
                + "\nto: " + castKey + ".value")).content();
        updated = documents.insertField(new YamlMappingInsertRequest(updated, connectionsPath,
                operation.key() + "-out", "from: " + castKey + ".result\nto: "
                + graphEndpoint(targetNode, target))).content();
        affected.add(nodesPath + "/" + castKey);
        affected.add(connectionsPath + "/" + operation.key() + "-in");
        affected.add(connectionsPath + "/" + operation.key() + "-out");
        return documents.parse(updated);
    }

    private YamlDocumentResponse addVariable(GraphMutationRequest request, String content,
                                              GraphMutationOperation operation, Set<String> affected) {
        String name = operation.key();
        requireSimpleKey(name, request, operation); requireValueType(operation.valueType(), request, operation);
        String variables = variableParent(request, operation);
        StringBuilder body = new StringBuilder("type: ").append(operation.valueType());
        if (operation.defaultValue() != null) {
            String literal = yamlTypedScalar(operation.defaultValue(), operation.valueType());
            YamlDocumentNode candidate = child(documents.parse("value: " + literal + "\n").root(), "value");
            if (!literalCompatible(operation.valueType(), candidate))
                throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_VARIABLE_DEFAULT",
                        "The variable default does not match " + operation.valueType(), request.path(), variables);
            body.append("\ndefault: ").append(literal);
        }
        if (find(documents.parse(content).root(), variables) == null) {
            String descriptor = parentPath(variables), owner = parentPath(descriptor);
            String hook = descriptor.substring(descriptor.lastIndexOf('/') + 1);
            if (hook.isBlank()) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "VARIABLES_MAPPING_REQUIRED",
                    "This graph has no variables mapping", request.path(), variables);
            String graph = "variables:\n  " + name + ":\n" + indentYaml(body.toString(), 4)
                    + "nodes: {}\nconnections: {}";
            affected.add(descriptor);
            return documents.insertField(new YamlMappingInsertRequest(content, owner, hook, graph));
        }
        affected.add(variables + "/" + name);
        return documents.insertField(new YamlMappingInsertRequest(content, variables, name, body.toString()));
    }

    private YamlDocumentResponse renameVariable(GraphMutationRequest request, String content,
                                                 GraphMutationOperation operation, Set<String> affected) {
        String oldName = operation.parameterName(), newName = operation.newName();
        requireSimpleKey(oldName, request, operation); requireSimpleKey(newName, request, operation);
        String variables = variableParent(request, operation), oldPath = variables + "/" + oldName;
        if (find(documents.parse(content).root(), oldPath) == null)
            throw error(HttpStatus.NOT_FOUND, "VARIABLE_NOT_FOUND", "The variable no longer exists",
                    request.path(), oldPath);
        String updated = documents.renameMappingKey(content, oldPath, newName).content();
        List<YamlDocumentNode> references = scalarNodes(documents.parse(updated).root()).stream()
                .filter(node -> "variable".equals(node.key()) && oldName.equals(node.value())).toList();
        for (YamlDocumentNode reference : references)
            updated = documents.edit(new YamlEditRequest(updated, reference.path(), newName)).content();
        affected.add(oldPath); affected.add(variables + "/" + newName);
        return documents.parse(updated);
    }

    private YamlDocumentResponse deleteVariable(GraphMutationRequest request, String content,
                                                 GraphMutationOperation operation, Set<String> affected) {
        String name = operation.parameterName() == null ? operation.key() : operation.parameterName();
        requireSimpleKey(name, request, operation);
        String variables = variableParent(request, operation), path = variables + "/" + name;
        YamlDocumentResponse parsed = documents.parse(content);
        if (find(parsed.root(), path) == null) throw error(HttpStatus.NOT_FOUND, "VARIABLE_NOT_FOUND",
                "The variable no longer exists", request.path(), path);
        boolean used = scalarNodes(parsed.root()).stream().anyMatch(node -> "variable".equals(node.key())
                && name.equals(node.value()) && !node.path().startsWith(path + "/"));
        if (used) throw error(HttpStatus.CONFLICT, "VARIABLE_IN_USE",
                "Delete or replace every getter and setter for this variable first", request.path(), path);
        affected.add(path);
        return documents.structure(new YamlStructureRequest(content, YamlStructureRequest.Operation.DELETE, path, null));
    }

    private YamlDocumentResponse changeVariableType(GraphMutationRequest request, EditorGraphProjection graph,
                                                     String content, GraphMutationOperation operation,
                                                     Set<String> affected) {
        String name = operation.parameterName() == null ? operation.key() : operation.parameterName();
        requireSimpleKey(name, request, operation); requireValueType(operation.valueType(), request, operation);
        String variables = variableParent(request, operation), path = variables + "/" + name;
        YamlDocumentResponse parsed = documents.parse(content);
        if (find(parsed.root(), path) == null) throw error(HttpStatus.NOT_FOUND, "VARIABLE_NOT_FOUND",
                "The variable no longer exists", request.path(), path);
        Set<String> dependentPaths = scalarNodes(parsed.root()).stream().filter(node -> "variable".equals(node.key())
                && name.equals(node.value())).map(node -> parentPath(node.path())).collect(java.util.stream.Collectors.toSet());
        boolean connected = graph.nodes().stream().filter(node -> dependentPaths.contains(node.yamlPath()))
                .flatMap(node -> node.pins().stream()).anyMatch(pin -> graph.edges().stream()
                        .anyMatch(edge -> edge.sourcePinId().equals(pin.id()) || edge.targetPinId().equals(pin.id())));
        if (connected) throw error(HttpStatus.CONFLICT, "VARIABLE_TYPE_CONNECTED",
                "Disconnect this variable's getter and setter nodes before changing its type", request.path(), path);
        String typePath = path + "/type";
        affected.add(typePath);
        return documents.edit(new YamlEditRequest(content, typePath, operation.valueType()));
    }

    private YamlDocumentResponse promoteToVariable(GraphMutationRequest request, EditorGraphProjection graph,
                                                    String content, GraphMutationOperation operation,
                                                    Set<String> affected) {
        String pinId = operation.sourcePinId() != null ? operation.sourcePinId() : operation.targetPinId();
        GraphPin selected = graph.ports().stream().filter(pin -> pin.id().equals(pinId)).findFirst()
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "PIN_NOT_FOUND", "The selected pin no longer exists",
                        request.path(), operation.yamlPath()));
        if (!selected.channel().equals("DATA")) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DATA_PIN_REQUIRED",
                "Only data pins can be promoted to variables", request.path(), selected.yamlPath());
        String name = operation.key(); requireSimpleKey(name, request, operation);
        GraphNode owner = node(graph, selected.nodeId()); String descriptor = descriptor(owner);
        if (descriptor == null || find(documents.parse(content).root(), descriptor + "/variables") == null)
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "EXPLICIT_GRAPH_REQUIRED",
                    "Variables can only be created in an existing explicit graph", request.path(), owner.yamlPath());
        String nodeKey = (selected.direction().equals("INPUT") ? "get-" : "set-") + name;
        String variables = descriptor + "/variables", nodes = descriptor + "/nodes", connections = descriptor + "/connections";
        YamlDocumentNode root = documents.parse(content).root();
        if (find(root, variables + "/" + name) != null || find(root, nodes + "/" + nodeKey) != null)
            throw error(HttpStatus.CONFLICT, "VARIABLE_KEY_EXISTS",
                    "The promoted variable or generated node already exists", request.path(), variables);
        String variableBody = "type: " + selected.valueType();
        String promotedDefault = selected.literal() == null ? null
                : selected.literal().value() != null ? selected.literal().value() : selected.literal().defaultValue();
        if (selected.direction().equals("INPUT") && promotedDefault != null)
            variableBody += "\ndefault: " + yamlTypedScalar(promotedDefault, selected.valueType());
        String updated = documents.insertField(new YamlMappingInsertRequest(content, variables, name, variableBody)).content();
        updated = documents.insertField(new YamlMappingInsertRequest(updated, nodes, nodeKey,
                "type: " + (selected.direction().equals("INPUT") ? "get-variable" : "set-variable")
                        + "\nvariable: " + name)).content();
        String connectionKey = "promote-" + name;
        if (find(documents.parse(updated).root(), connections + "/" + connectionKey) != null)
            throw error(HttpStatus.CONFLICT, "CONNECTION_KEY_EXISTS",
                    "The generated promotion connection already exists", request.path(), connections);
        String from = selected.direction().equals("INPUT") ? nodeKey + ".value" : graphEndpoint(owner, selected);
        String to = selected.direction().equals("INPUT") ? graphEndpoint(owner, selected) : nodeKey + ".value";
        updated = documents.insertField(new YamlMappingInsertRequest(updated, connections, connectionKey,
                "from: " + from + "\nto: " + to)).content();
        affected.add(variables + "/" + name); affected.add(nodes + "/" + nodeKey);
        affected.add(connections + "/" + connectionKey);
        return documents.parse(updated);
    }

    private static String variableParent(GraphMutationRequest request, GraphMutationOperation operation) {
        String parent = operation.parentYamlPath();
        if (parent == null || parent.isBlank()) parent = normalizeRoot(request.yamlPath()) + "/variables";
        else if (!parent.endsWith("/variables") && !parent.equals("/variables")) parent += "/variables";
        return parent.replaceFirst("^//", "/");
    }

    private YamlDocumentResponse insert(GraphMutationRequest request, String content,
                                        GraphMutationOperation operation, Set<String> affected, SignedSchemas signed) {
        requirePath(operation.parentYamlPath(), request, operation);
        String kind = operation.nodeKind();
        if (kind == null) unsupported(request, operation, "A node kind is required");
        if (kind.startsWith("extension-") && !signed.allows(extensionContentType(kind), operation.value()))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "UNSIGNED_EXTENSION_SCHEMA",
                    "The extension node type is not present in the current signed schema catalog",
                    request.path(), operation.parentYamlPath());
        affected.add(operation.parentYamlPath());
        boolean resourceNodeMapping="dialogue".equals(request.resourceKind())&&"dialogue-entry".equals(kind)
                &&operation.parentYamlPath().equals((normalizeRoot(request.yamlPath())+"/nodes").replaceFirst("^//","/"));
        if(!resourceNodeMapping&&(operation.parentYamlPath().endsWith("/nodes") || operation.parentYamlPath().equals("/nodes"))){
            requireSimpleKey(operation.key(),request,operation);
            String graphKind = request.resourceKind().equals("dialogue") ? "dialogue" : "script";
            String yaml=template(graphKind,kind,operation.key(),operation.value());if(yaml.startsWith("- "))yaml=yaml.substring(2).replace("\n  ","\n");
            YamlDocumentResponse parsed=documents.parse(content);
            if(find(parsed.root(),operation.parentYamlPath())==null){String descriptor=parentPath(operation.parentYamlPath()),owner=parentPath(descriptor),hook=descriptor.substring(descriptor.lastIndexOf('/')+1);String body="variables: {}\nnodes:\n  "+operation.key()+":\n"+indentYaml(yaml,4)+"connections: {}";affected.add(descriptor);return documents.insertField(new YamlMappingInsertRequest(content,owner,hook,body));}
            affected.add(operation.parentYamlPath()+"/"+operation.key());return documents.insertField(new YamlMappingInsertRequest(content,operation.parentYamlPath(),operation.key(),yaml));
        }
        if ("dialogue-entry".equals(kind)) {
            requireSimpleKey(operation.key(), request, operation);
            return documents.insertField(new YamlMappingInsertRequest(content, operation.parentYamlPath(),
                    operation.key(), "graph:\n  variables: {}\n  nodes:\n    line:\n      type: say\n      text: \"New line\"\n  connections:\n    enter: { from: $event.exec, to: line.exec }"));
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
            if (Set.of("on-start", "on-complete", "on-fail", "on-reset", "on-click", "on-damage",
                    "on-spawn", "on-despawn", "on-no-dialogue", "graph").contains(hook)
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
        if(!source.channel().equals(target.channel()))throw error(HttpStatus.UNPROCESSABLE_ENTITY,"PIN_CHANNEL_MISMATCH",
                "An "+source.channel().toLowerCase(Locale.ROOT)+" output cannot connect to a "+target.channel().toLowerCase(Locale.ROOT)+" input",
                request.path(),target.yamlPath());
        if(source.channel().equals("DATA")&&!source.valueType().equals(target.valueType()))throw error(HttpStatus.UNPROCESSABLE_ENTITY,"PIN_VALUE_TYPE_MISMATCH",
                "The "+source.valueType()+" output requires an exact "+source.valueType()+" input; target is "+target.valueType(),request.path(),target.yamlPath());
        if (!compatible(source, target, graph))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PIN_TYPE",
                    "The " + source.semanticType() + " output cannot connect to a " + target.semanticType() + " input",
                    request.path(), target.yamlPath());
        if (!cycleAllowed(source, target) && (sourceNode.id().equals(targetNode.id()) || reaches(graph, targetNode.id(), sourceNode.id())))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "CYCLE_NOT_ALLOWED",
                    "This connection would create a cycle that is not allowed here", request.path(), targetNode.yamlPath());
        long inbound = graph.edges().stream().filter(edge -> edge.targetPinId().equals(target.id())).count();
        String descriptor = commonDescriptor(sourceNode, targetNode);
        if (descriptor != null) {
            requireSimpleKey(operation.key(), request, operation);
            String updated = content;
            boolean occupiedSource = "EXECUTION".equals(source.channel())
                    && Set.of("ZERO_OR_ONE", "EXACTLY_ONE").contains(source.cardinality())
                    && graph.edges().stream().anyMatch(edge -> edge.sourcePinId().equals(source.id()));
            if (Set.of("ZERO_OR_ONE", "EXACTLY_ONE").contains(target.cardinality()) && inbound > 0 || occupiedSource) {
                List<GraphEdge> replaced = graph.edges().stream().filter(edge -> edge.targetPinId().equals(target.id())
                                || occupiedSource && edge.sourcePinId().equals(source.id()))
                        .distinct()
                        .sorted(Comparator.comparing(GraphEdge::sourceYamlPath).reversed()).toList();
                for (GraphEdge edge : replaced) {
                    String connection = parentPath(edge.sourceYamlPath());
                    updated = documents.structure(new YamlStructureRequest(updated, YamlStructureRequest.Operation.DELETE,
                            connection, null)).content(); affected.add(connection);
                }
            }
            String connections = descriptor + "/connections";
            affected.add(connections + "/" + operation.key());
            return documents.insertField(new YamlMappingInsertRequest(updated, connections, operation.key(),
                    "from: " + graphEndpoint(sourceNode, source) + "\nto: " + graphEndpoint(targetNode, target)));
        }
        if (Set.of("ZERO_OR_ONE", "EXACTLY_ONE").contains(target.cardinality()) && inbound > 0
                && !"behavior".equals(request.resourceKind()))
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
                String dialogueDescriptor = sourceNode.yamlPath() + "/graph";
                if (find(documents.parse(content).root(), dialogueDescriptor) != null)
                    throw unsupported(request, operation,
                            "Add and connect a goto node inside the existing dialogue event graph");
                requireSimpleKey(operation.key(), request, operation);
                String dialogueGraph = "variables: {}\nnodes:\n  " + operation.key() + ":\n"
                        + "    type: goto\n    node: " + targetNode.title() + "\nconnections:\n"
                        + "  enter: { from: $event.exec, to: " + operation.key() + ".exec }";
                affected.add(dialogueDescriptor);
                yield documents.insertField(new YamlMappingInsertRequest(content, sourceNode.yamlPath(), "graph", dialogueGraph));
            }
            case "quest" -> connectQuest(request, content, sourceNode, targetNode, operation, affected);
            case "script" -> {
                requireSimpleKey(operation.key(),request,operation);String connections=normalizeRoot(request.yamlPath())+"/connections";
                affected.add(connections+"/"+operation.key());
                yield documents.insertField(new YamlMappingInsertRequest(content,connections,operation.key(),
                        "from: "+graphEndpoint(sourceNode,source)+"\nto: "+graphEndpoint(targetNode,target)));
            }
            default -> throw unsupported(request, operation,
                    "This content graph does not expose a compatible connect mutation (source "
                            + sourceNode.kind() + " at " + sourceNode.yamlPath() + ", target "
                            + targetNode.kind() + " at " + targetNode.yamlPath() + ")");
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
                (operation.edgeId() == null || candidate.id().equals(operation.edgeId()))
                        && (operation.sourcePinId() == null || candidate.sourcePinId().equals(operation.sourcePinId()))
                        && (operation.targetPinId() == null || candidate.targetPinId().equals(operation.targetPinId()))
                        && (operation.yamlPath() == null || operation.yamlPath().equals(candidate.sourceYamlPath())))
                .findFirst().orElseThrow(() -> error(HttpStatus.NOT_FOUND, "EDGE_NOT_FOUND",
                        "The connection no longer exists", request.path(), operation.yamlPath()));
        if ("behavior".equals(request.resourceKind()))
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ORPHAN_NOT_ALLOWED",
                    "Behaviour nodes cannot be left disconnected; reconnect or delete the branch",
                    request.path(), edge.targetYamlPath());
        if(edge.id().startsWith("graph-edge:") || "script".equals(request.resourceKind())){String connectionPath=parentPath(edge.sourceYamlPath());affected.add(connectionPath);return documents.structure(new YamlStructureRequest(content,YamlStructureRequest.Operation.DELETE,connectionPath,null));}
        if (!Set.of("dialogue", "quest").contains(request.resourceKind()))
            throw unsupported(request, operation, "This connection is implicit in YAML ordering and cannot be disconnected");
        if (edge.sourceYamlPath() == null || edge.sourceYamlPath().isBlank())
            throw unsupported(request, operation, "This implicit connection cannot be disconnected directly");
        affected.add(edge.sourceYamlPath());
        return documents.structure(new YamlStructureRequest(content, YamlStructureRequest.Operation.DELETE,
                edge.sourceYamlPath(), null));
    }

    private static String graphEndpoint(GraphNode node,GraphPin pin){String owner=switch(node.kind()){case "script-input"->"$input";case "script-output"->"$output";case "event"->"$event";default->node.title();};return owner+"."+pin.label();}
    private static String descriptor(GraphNode node){String path=node.yamlPath();if(path==null)return null;if(node.kind().equals("event"))return path;if(node.kind().equals("script-input")||node.kind().equals("script-output"))return parentPath(path);int marker=path.lastIndexOf("/nodes/");return marker<0?null:path.substring(0,marker);}
    private static String commonDescriptor(GraphNode source,GraphNode target){String left=descriptor(source),right=descriptor(target);return left!=null&&left.equals(right)?left:null;}

    private EditorGraphProjection project(GraphMutationRequest request, String content, SignedSchemas signed) {
        return projections.project(new GraphProjectionRequest(request.path(), request.resourceKind(),
                request.resourceId(), request.yamlPath(), content, sha256(content),
                currentFiles(request, content)), signed.schemas(), signed.version());
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
                case "sequence" -> "- type: sequence\n  count: 2";
                case "branch" -> "- type: branch\n  condition: true";
                case "switch" -> "- type: switch\n  value-type: string\n  cases: [value]";
                case "gate" -> "- type: gate\n  initially-open: true";
                case "do-once" -> "- type: do-once";
                case "do-n" -> "- type: do-n\n  n: 1";
                case "for" -> "- type: for\n  first: 0\n  last: 1\n  step: 1";
                case "for-each" -> "- type: for-each\n  element-type: string\n  items: []";
                case "while" -> "- type: while\n  condition: false";
                case "extension-command" -> "- type: " + extensionType;
                default -> {
                    if (EXPLICIT_SCRIPT_COMMANDS.contains(nodeKind)) yield "- type: " + nodeKind;
                    throw new IllegalArgumentException("Unsupported quest node kind");
                }
            };
            case "dialogue", "script", "npc" -> switch (nodeKind) {
                case "say" -> "- type: say\n  text: \"New line\"";
                case "wait" -> "- type: wait\n  duration: 1s";
                case "choice" -> "- type: choice\n  options:\n    - text: Continue";
                case "sequence" -> "- type: sequence\n  count: 2";
                case "switch" -> "- type: switch\n  value-type: string\n  cases: [value]";
                case "random" -> "- type: random\n  weights: [1, 1]";
                case "gate" -> "- type: gate\n  initially-open: true";
                case "do-once" -> "- type: do-once";
                case "do-n" -> "- type: do-n\n  n: 1";
                case "for" -> "- type: for\n  first: 0\n  last: 1\n  step: 1";
                case "for-each" -> "- type: for-each\n  element-type: string\n  items: []";
                case "while" -> "- type: while\n  condition: false";
                case "get-variable" -> "- type: get-variable\n  variable: value";
                case "set-variable-node" -> "- type: set-variable\n  variable: value";
                case "get-player-flag" -> "- type: get-player-flag\n  name: flag";
                case "set-player-flag" -> "- type: set-player-flag\n  name: flag\n  value: true";
                case "get-player-string" -> "- type: get-player-string\n  name: value";
                case "set-player-string" -> "- type: set-player-string\n  name: value\n  value: \"\"";
                case "run-script" -> {
                    String target = extensionType == null ? "example" : extensionType;
                    if (!target.matches("[a-z0-9][a-z0-9_.:-]{0,127}"))
                        throw new IllegalArgumentException("A reusable script ID is required");
                    yield "- type: run-script\n  script: " + target + "\n  inputs: {}";
                }
                case "goto" -> {
                    if (resourceKind.equals("script")) throw new IllegalArgumentException("Goto is an inline dialogue node");
                    yield "- type: goto\n  node: start";
                }
                case "end-dialogue" -> {
                    if (resourceKind.equals("script")) throw new IllegalArgumentException("End-dialogue is an inline dialogue node");
                    yield "- type: end-dialogue";
                }
                case "stop" -> "- type: stop";
                case "branch" -> "- type: branch\n  condition: true";
                case "integer-to-number", "string-to-text" -> "- type: " + nodeKind;
                case "equals", "not-equals", "greater-than", "greater-than-or-equal", "less-than", "less-than-or-equal" -> {
                    if (extensionType == null || !extensionType.matches("[a-z0-9][a-z0-9_.:-]{0,127}"))
                        throw new IllegalArgumentException("A bounded nominal operand type is required");
                    yield "- type: " + nodeKind + "\n  value-type: " + extensionType;
                }
                case "and", "or", "not" -> "- type: " + nodeKind;
                case "to-string" -> {
                    if (extensionType == null || !extensionType.matches("[a-z0-9][a-z0-9_.:-]{0,127}"))
                        throw new IllegalArgumentException("A bounded nominal source type is required");
                    yield "- type: to-string\n  value-type: " + extensionType;
                }
                case "extension-command" -> "- type: " + extensionType;
                default -> {
                    if (EXPLICIT_SCRIPT_COMMANDS.contains(nodeKind))
                        yield "- type: " + nodeKind;
                    throw new IllegalArgumentException("Unsupported script node kind");
                }
            };
            default -> throw new IllegalArgumentException("Unsupported resource kind");
        };
    }

    private static void requireExtensionType(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9_.-]{0,62}:[a-z0-9][a-z0-9_.-]{0,62}"))
            throw new IllegalArgumentException("Extension node type must be a bounded namespaced ID");
    }

    private static String extensionContentType(String kind) {
        return switch (kind) {
            case "extension-action" -> "behavior-action";
            case "extension-condition" -> "behavior-condition";
            case "extension-objective" -> "objective";
            case "extension-command" -> "command";
            default -> "unknown";
        };
    }

    private record SignedSchemas(List<EditorSchemaDocument> schemas, Set<String> keys, String version) {
        private SignedSchemas(List<EditorSchemaDocument> schemas, String version) {
            this(schemas == null ? List.of() : List.copyOf(schemas),
                    schemas == null ? Set.of() : schemas.stream()
                            .map(schema -> schema.contentType() + "\0" + schema.typeId())
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    version == null ? "none" : version);
        }
        private boolean allows(String contentType, String typeId) {
            return typeId != null && keys.contains(contentType + "\0" + typeId);
        }
    }

    private static boolean compatible(String source, String target) {
        return Objects.equals(source, target) || (source != null && target != null
                && (source.equals("reference") && target.startsWith("reference:" )
                || target.equals("reference") && source.startsWith("reference:")));
    }

    private static boolean compatible(GraphPin source, GraphPin target, EditorGraphProjection graph) {
        if(!source.channel().equals(target.channel()))return false;
        boolean semantic = source.channel().equals("DATA")?source.valueType().equals(target.valueType()):compatible(source.semanticType(), target.semanticType());
        boolean sourceAllows = source.compatibility().semanticTypes().isEmpty()
                || source.compatibility().semanticTypes().stream().anyMatch(type -> compatible(type, target.semanticType()));
        boolean targetAllows = target.compatibility().semanticTypes().isEmpty()
                || target.compatibility().semanticTypes().stream().anyMatch(type -> compatible(source.semanticType(), type));
        Set<String> required = new LinkedHashSet<>(source.compatibility().capabilityRequirements());
        required.addAll(target.compatibility().capabilityRequirements());
        return semantic && sourceAllows && targetAllows && graph.capabilities().containsAll(required)
                && source.compatibility().resourceScopes().contains("CURRENT_RESOURCE")
                && target.compatibility().resourceScopes().contains("CURRENT_RESOURCE");
    }

    private static boolean cycleAllowed(GraphPin source, GraphPin target) {
        return "ALLOW".equals(source.compatibility().cyclePolicy())
                && "ALLOW".equals(target.compatibility().cyclePolicy());
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
        GraphPin value = graph.ports().stream().filter(port -> port.id().equals(id)).findFirst().orElse(null);
        if (value == null)
            throw new GraphContractException(HttpStatus.NOT_FOUND, "PIN_NOT_FOUND",
                    "The " + direction + " pin no longer exists", request.path(), operation.yamlPath(), null,
                    operation.nodeId(), id, null, false, graph.contentDigest(), request.expectedProjectRevision());
        if (!value.direction().equals(direction.toUpperCase(Locale.ROOT)))
            throw new GraphContractException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PIN_DIRECTION",
                    "The selected port has the wrong connection direction", request.path(), value.yamlPath(),
                    value.sourceRange(), value.nodeId(), value.id(), null, false,
                    graph.contentDigest(), request.expectedProjectRevision());
        return value;
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
        boolean explicitGraphNodes = (parentPath.endsWith("/nodes") || parentPath.equals("/nodes"))
                && graph.nodes().stream().anyMatch(node -> {
                    String descriptor = descriptor(node);
                    return descriptor != null && (descriptor + "/nodes").replaceFirst("^//", "/").equals(parentPath);
                });
        if (explicitGraphNodes) return;
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
                            (normalizeRoot(request.yamlPath()) + "/on-click").replaceFirst("^//", "/"),
                            (normalizeRoot(request.yamlPath()) + "/on-damage").replaceFirst("^//", "/"),
                            (normalizeRoot(request.yamlPath()) + "/on-spawn").replaceFirst("^//", "/"),
                            (normalizeRoot(request.yamlPath()) + "/on-despawn").replaceFirst("^//", "/"),
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
                || request.operations().isEmpty() || request.operations().size() > MAX_OPERATIONS
                || request.requestId() != null && !request.requestId().matches("[A-Za-z0-9_.:-]{1,128}"))
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

    private static List<GraphMutationOperation> flatten(List<GraphMutationOperation> operations) {
        List<GraphMutationOperation> result = new ArrayList<>();
        flattenInto(operations, result, 0);
        if (result.isEmpty() || result.size() > MAX_OPERATIONS)
            throw error(HttpStatus.BAD_REQUEST, "COMPOUND_LIMIT",
                    "Compound graph mutation exceeds its operation bound", null, null);
        return List.copyOf(result);
    }

    private static void flattenInto(List<GraphMutationOperation> operations,
                                    List<GraphMutationOperation> result, int depth) {
        if (depth > 4) throw error(HttpStatus.BAD_REQUEST, "COMPOUND_DEPTH_LIMIT",
                "Compound graph mutation exceeds its nesting bound", null, null);
        for (GraphMutationOperation operation : operations) {
            if (operation == null || operation.type() == null)
                throw error(HttpStatus.BAD_REQUEST, "INVALID_OPERATION", "Graph operation type is required", null, null);
            if (operation.type() == GraphMutationOperation.Type.COMPOUND
                    || operation.type() == GraphMutationOperation.Type.INSERT_ON_WIRE) {
                if (operation.children().isEmpty()) throw error(HttpStatus.BAD_REQUEST, "INVALID_COMPOUND",
                        "Compound graph operations require primitive children", null, operation.yamlPath());
                flattenInto(operation.children(), result, depth + 1);
            } else {
                if (!operation.children().isEmpty()) throw error(HttpStatus.BAD_REQUEST, "INVALID_OPERATION_CHILDREN",
                        "Primitive graph operations cannot contain children", null, operation.yamlPath());
                result.add(operation);
                if (result.size() > MAX_OPERATIONS) throw error(HttpStatus.BAD_REQUEST, "COMPOUND_LIMIT",
                        "Compound graph mutation exceeds its operation bound", null, operation.yamlPath());
            }
        }
    }

    private static void requireExpectedRange(EditorGraphProjection graph, GraphMutationOperation operation,
                                             GraphMutationRequest request) {
        SourceRange expected = operation.expectedSourceRange();
        if (expected == null) return;
        String path = operation.yamlPath() != null ? operation.yamlPath() : operation.targetYamlPath();
        GraphNode owner = graph.nodes().stream().filter(node -> Objects.equals(node.yamlPath(), path))
                .findFirst().orElse(null);
        SourceRange actual = owner == null ? null : owner.range();
        if (!Objects.equals(expected, actual))
            throw new GraphContractException(HttpStatus.CONFLICT, "STALE_SOURCE_RANGE",
                    "The expected YAML source range changed before the operation was applied",
                    request.path(), path, actual, owner == null ? operation.nodeId() : owner.id(),
                    operation.sourcePinId() != null ? operation.sourcePinId() : operation.targetPinId(), null,
                    true, graph.contentDigest(), request.expectedProjectRevision());
    }

    private static GraphMutationResponse.SourcePatch minimalPatch(String path, String before, String after) {
        int prefix = 0, maximum = Math.min(before.length(), after.length());
        while (prefix < maximum && before.charAt(prefix) == after.charAt(prefix)) prefix++;
        int beforeSuffix = before.length(), afterSuffix = after.length();
        while (beforeSuffix > prefix && afterSuffix > prefix
                && before.charAt(beforeSuffix - 1) == after.charAt(afterSuffix - 1)) {
            beforeSuffix--; afterSuffix--;
        }
        return new GraphMutationResponse.SourcePatch(path, prefix, beforeSuffix, prefix, afterSuffix,
                before.substring(prefix, beforeSuffix), after.substring(prefix, afterSuffix));
    }
}
