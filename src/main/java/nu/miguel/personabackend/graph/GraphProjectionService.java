package nu.miguel.personabackend.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.persona.editor.protocol.EditorSchemaDocument;
import nu.miguel.personabackend.document.*;
import nu.miguel.personabackend.reference.*;
import nu.miguel.personabackend.project.ProjectContentRules;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import static nu.miguel.personabackend.graph.EditorGraphProjection.*;

@Service
public final class GraphProjectionService {
    static final int MAX_NODES = 10_000;
    static final int MAX_EDGES = 20_000;
    private static final Set<String> KINDS = Set.of("behavior", "dialogue", "quest", "npc", "script", "other");
    private final YamlDocumentService documents;
    private final ProjectReferenceService references;
    private final ProjectContentRules projectRules;

    public GraphProjectionService(YamlDocumentService documents, ProjectReferenceService references,
                                  ProjectContentRules projectRules) {
        this.documents = documents; this.references = references; this.projectRules = projectRules;
    }

    public EditorGraphProjection project(GraphProjectionRequest request) {
        return project(request, List.of(), "none");
    }

    public EditorGraphProjection project(GraphProjectionRequest request, List<EditorSchemaDocument> schemas,
                                         String schemaCatalogVersion) {
        requireRequest(request);
        GraphRequestBounds.requireProjectFiles(request.projectFiles(), request.path(), request.yamlPath());
        String digest = sha256(request.content());
        if (!constantEquals(digest, request.expectedDigest()))
            throw error(HttpStatus.CONFLICT, "STALE_CONTENT", "The document digest is stale", request.path(), request.yamlPath());
        YamlDocumentResponse document = documents.parse(request.content());
        if (!document.valid())
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_YAML",
                    document.diagnostics().isEmpty() ? "The selected document contains invalid YAML"
                            : document.diagnostics().getFirst().message(), request.path(), request.yamlPath());
        YamlDocumentNode root = find(document.root(), normalizeRoot(request.yamlPath()));
        if (root == null) throw error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "The requested resource YAML path does not exist", request.path(), request.yamlPath());
        ProjectReferenceGraph graph = request.projectFiles().isEmpty()
                ? new ProjectReferenceGraph(List.of(), List.of()) : references.analyze(request.projectFiles());
        Builder builder = new Builder(request, document.root(), graph, schemas, schemaCatalogVersion);
        switch (request.resourceKind()) {
            case "behavior" -> builder.behavior(root);
            case "dialogue" -> builder.dialogue(root);
            case "quest" -> builder.quest(root);
            case "npc" -> builder.npc(root);
            case "script" -> builder.script(root);
            default -> builder.other(root);
        }
        builder.customFallback(root);
        builder.referenceDiagnostics();
        return builder.finish(digest);
    }

    public EditorGraphProjection relationship(RelationshipProjectionRequest request) {
        if (request == null) throw error(HttpStatus.BAD_REQUEST, "INVALID_RELATIONSHIP_REQUEST",
                "Missing Relationship Map request", null, null);
        var verified = projectRules.verify(request.files(), request.expectedRevision());
        ProjectReferenceGraph graph = references.analyze(List.copyOf(verified.files().values()));
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        List<GraphDiagnostic> diagnostics = new ArrayList<>();
        Map<String, GraphNode> byIdentity = new LinkedHashMap<>();
        Map<String,Set<String>> referenceTypes=new HashMap<>();
        for(ProjectReference reference:graph.references())referenceTypes.computeIfAbsent(reference.sourceType()+"\0"+reference.sourceId(),ignored->new LinkedHashSet<>()).add(reference.targetType());
        for (ProjectDeclaration declaration : graph.declarations()) {
            String id = relationshipId(declaration.type(), declaration.id());
            SourceRange range = new SourceRange(0, 0, declaration.line(), declaration.column(),
                    declaration.line(), declaration.column());
            String yamlPath = declaration.type().equals("script") ? "/scripts/" + declaration.id() : "/id";
            List<GraphPin> pins=new ArrayList<>();
            pins.add(new GraphPin(id + ":in", id, "input", "reference:"+declaration.type(), "many", false, "inbound", yamlPath));
            for(String targetType:referenceTypes.getOrDefault(declaration.type()+"\0"+declaration.id(),Set.of()))
                pins.add(new GraphPin(id+":out:reference:"+targetType,id,"output","reference:"+targetType,"many",false,targetType,yamlPath));
            GraphNode node = new GraphNode(id, yamlPath, range, "relationship-" + declaration.type(),
                    declaration.id(), declaration.path(), List.of(),
                    pins,
                    List.of(), false, null);
            nodes.add(node); byIdentity.put(declaration.type() + "\0" + declaration.id(), node);
        }
        Map<String, List<String>> adjacency = new HashMap<>();
        for (ProjectReference reference : graph.references()) if (reference.resolved())
            adjacency.computeIfAbsent(reference.sourceType() + "\0" + reference.sourceId(), ignored -> new ArrayList<>())
                    .add(reference.targetType() + "\0" + reference.targetId());
        for (ProjectReference reference : graph.references()) {
            String sourceKey = reference.sourceType() + "\0" + reference.sourceId();
            GraphNode source = byIdentity.get(sourceKey);
            if (source == null) continue;
            String targetKey = reference.targetType() + "\0" + reference.targetId();
            GraphNode target = byIdentity.get(targetKey);
            if (target == null) {
                String id = relationshipId("missing-" + reference.targetType(), reference.targetId());
                target = byIdentity.get("missing\0" + targetKey);
                if (target == null) {
                    SourceRange range = new SourceRange(0, 0, reference.line(), reference.column(),
                            reference.line(), reference.column());
                    target = new GraphNode(id, "", range, "missing-reference", reference.targetId(),
                            "Missing " + reference.targetType(), List.of(),
                            List.of(new GraphPin(id + ":in", id, "input", "reference:" + reference.targetType(),
                                    "many", true, "unresolved", "")), List.of("unresolved"), false, null);
                    nodes.add(target); byIdentity.put("missing\0" + targetKey, target);
                }
            }
            boolean cyclic = reference.resolved() && relationshipPath(adjacency, targetKey, sourceKey, new HashSet<>());
            String sourcePin = source.id() + ":out:reference:"+reference.targetType(), targetPin = target.id() + ":in";
            edges.add(new GraphEdge("relationship:" + edges.size(), sourcePin, targetPin,
                    "reference:" + reference.targetType(), reference.targetType(), reference.yamlPath(),
                    target.yamlPath(), reference.resolved(), cyclic));
            if (!reference.resolved()) diagnostics.add(new GraphDiagnostic("UNRESOLVED_REFERENCE", "ERROR",
                    "Missing " + reference.targetType() + " " + reference.targetId(), reference.path(),
                    reference.yamlPath(), new SourceRange(0, 0, reference.line(), reference.column(),
                    reference.line(), reference.column()), source.id(), reference.targetType(), reference.targetId()));
        }
        if (nodes.size() > MAX_NODES || edges.size() > MAX_EDGES)
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "GRAPH_LIMIT", "Relationship Map exceeds graph limits", null, null);
        return new EditorGraphProjection(VERSION, "project:relationship-map", "relationship", "project",
                "", "", verified.revision(), false, nodes, edges, diagnostics,
                List.of("SELECT", "PAN_ZOOM", "AUTO_LAYOUT", "INSPECT", "OPEN_SOURCE", "OPEN_TARGET"));
    }

    private static final class Builder {
        private static final ObjectMapper JSON = new ObjectMapper();
        private static final Set<String> BEHAVIOR_ROOT = Set.of("content-version", "id", "scope", "root");
        private static final Set<String> DIALOGUE_ROOT = Set.of("content-version", "id", "start", "nodes");
        private static final Set<String> QUEST_ROOT = Set.of("content-version", "id", "title", "description", "phases",
                "when", "requirements", "repeatable", "cooldown", "maximum-completions", "time-limit",
                "on-start", "on-complete", "on-fail", "on-reset");
        private static final Set<String> NPC_ROOT = Set.of("content-version", "id", "display-name", "shared-behavior",
                "player-behavior", "dialogues", "anchors", "presentation", "skin", "equipment", "age", "pose",
                "on-interact", "on-no-dialogue");
        private final GraphProjectionRequest request;
        private final YamlDocumentNode documentRoot;
        private final ProjectReferenceGraph referenceGraph;
        private final List<GraphNode> nodes = new ArrayList<>();
        private final List<GraphEdge> edges = new ArrayList<>();
        private final List<GraphDiagnostic> diagnostics = new ArrayList<>();
        private final Map<String, String> nodeByPath = new HashMap<>();
        private final Map<String, Integer> nodeIndexById = new HashMap<>();
        private final Map<String, String> identityByPath = new HashMap<>();
        private final Set<String> identities = new HashSet<>();
        private final Set<String> represented = new HashSet<>();
        private final Set<String> edgeIds = new HashSet<>();
        private final Set<String> signedSchemaTypes;
        private final Set<String> signedValueTypes;
        private final Map<String, EditorSchemaDocument> signedSchemas;
        private final String schemaCatalogVersion;

        private Builder(GraphProjectionRequest request, YamlDocumentNode documentRoot, ProjectReferenceGraph referenceGraph,
                        List<EditorSchemaDocument> schemas, String schemaCatalogVersion) {
            this.request = request; this.documentRoot = documentRoot; this.referenceGraph = referenceGraph;
            this.signedSchemaTypes = schemas == null ? Set.of() : schemas.stream()
                    .map(schema -> schema.contentType() + "\0" + schema.typeId()).collect(java.util.stream.Collectors.toUnmodifiableSet());
            this.signedSchemas = schemas == null ? Map.of() : schemas.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    schema -> schema.contentType() + "\0" + schema.typeId(), schema -> schema, (left, right) -> right));
            Set<String> valueTypes = new LinkedHashSet<>();
            if (schemas != null) for (EditorSchemaDocument schema : schemas) try {
                JSON.readTree(schema.schemaJson()).path("x-persona-value-types").fieldNames().forEachRemaining(valueTypes::add);
            } catch (Exception ignored) { /* Invalid signed schemas remain inert and diagnose at their node. */ }
            this.signedValueTypes = Set.copyOf(valueTypes);
            this.schemaCatalogVersion = schemaCatalogVersion == null ? "none" : schemaCatalogVersion;
        }

        private void behavior(YamlDocumentNode root) {
            YamlDocumentNode behaviorRoot = child(root, "root");
            if (behaviorRoot == null) {
                diagnostic("MISSING_ROOT", "ERROR", "Behaviour has no root node", root, null, null, null);
                add(root, "behavior", request.resourceId(), "Missing root", List.of(), List.of("error"), false);
                return;
            }
            behaviorNode(behaviorRoot, null, "root", 0);
            String id = identity(behaviorRoot, semanticId(behaviorRoot));
            YamlDocumentNode scope = child(root, "scope");
            if (scope != null) appendFields(id, List.of(field(scope, false)));
        }

        private void behaviorNode(YamlDocumentNode node, String parentId, String edgeLabel, int order) {
            if (!"mapping".equals(node.kind())) { addCustom(node, "Custom behaviour YAML"); return; }
            String type = value(node, "type", "unknown");
            List<YamlDocumentNode> children = behaviorChildren(node);
            List<GraphPin> pins = new ArrayList<>();
            String id = identity(node, semanticId(node));
            pins.add(pin(id, "input", parentId == null ? "execution" : "behavior-child",
                    "single", true, parentId == null ? "entry" : "parent", node.path()));
            if (type.equals("subtree")) pins.add(pin(id, "output", "behavior-reference",
                    "single", false, "behaviour", node.path()));
            for (int index = 0; index < children.size(); index++) {
                YamlDocumentNode child = children.get(index);
                String childId = identity(child, semanticId(child));
                pins.add(new GraphPin(id + ":out:child:" + pinToken(childId), id, "output",
                        "behavior-child", "single", true,
                        (index + 1) + (semanticId(child) == null ? " child" : " " + semanticId(child)),
                        child.path(), index, range(child),
                        new PortCompatibility(List.of("behavior-child"), List.of("CURRENT_RESOURCE"),
                                "DENY", List.of("CONNECT"))));
            }
            if (Set.of("sequence", "selector", "priority-selector", "parallel").contains(type)) {
                YamlDocumentNode childrenNode = child(node, "children");
                String childrenPath = node.path() + "/children";
                pins.add(new GraphPin(id + ":out:add-child", id, "output", "behavior-child",
                        "many", false, "+ child", childrenPath, children.size(),
                        childrenNode == null ? range(node) : range(childrenNode),
                        new PortCompatibility(List.of("behavior-child"), List.of("CURRENT_RESOURCE"),
                                "DENY", List.of("CONNECT"))));
            }
            List<String> badges = new ArrayList<>();
            String extensionType = type.contains(":") ? type
                    : type.equals("action") ? value(node, "action", null)
                    : type.equals("condition") ? value(node, "condition", null) : null;
            String extensionContentType = type.equals("action") ? "behavior-action"
                    : type.equals("condition") ? "behavior-condition" : null;
            if (extensionType != null && extensionType.contains(":")
                    && (extensionContentType == null || !signed(extensionContentType, extensionType))) {
                addCustom(node, "Custom YAML · unsigned extension " + extensionType); return;
            }
            if (extensionType != null && extensionType.contains(":")) badges.add("extension");
            if (Set.of("checkpoint", "wait", "cooldown").contains(type)) badges.add("durable");
            if (semanticId(node) == null) badges.add("missing stable id");
            addWithId(id, node, type, semanticId(node) == null ? type : semanticId(node), type, pins, badges,
                    false, extensionType != null && extensionType.contains(":")
                            ? extensionType.substring(0, extensionType.indexOf(':')) : null);
            if (parentId != null) connect(parentId, id, "behavior-child", edgeLabel, node.path(), node.path(), true, false, order);
            for (int index = 0; index < children.size(); index++)
                behaviorNode(children.get(index), id, children.size() > 1 ? Integer.toString(index + 1) : "child", index);
        }

        private void dialogue(YamlDocumentNode root) {
            YamlDocumentNode entries = child(root, "nodes");
            String start = value(root, "start", "");
            if (entries == null || !"mapping".equals(entries.kind())) {
                diagnostic("MISSING_DIALOGUE_NODES", "ERROR", "Dialogue has no nodes mapping", root, null, null, null);
                return;
            }
            for (YamlDocumentNode entry : entries.children()) {
                String id = identity(entry, entry.key());
                List<String> badges = new ArrayList<>();
                if (Objects.equals(start, entry.key())) badges.add("start");
                addWithId(id, entry, "dialogue-entry", entry.key(), Objects.equals(start, entry.key()) ? "Start node" : "Dialogue node",
                        List.of(pin(id, "input", "dialogue-flow", "many", false, "in", entry.path()),
                                pin(id, "output", "dialogue-flow", "many", false, "next", entry.path())),
                        badges, false, null);
            }
            Map<String, List<String>> transfers = new HashMap<>();
            for (YamlDocumentNode entry : entries.children()) {
                YamlDocumentNode script = child(entry, "script");
                if (script != null) projectSteps(script, identity(entry, entry.key()), "dialogue", transfers, entry.key(), null);
                if (script == null || !containsStepType(script, Set.of("goto", "end-dialogue")))
                    diagnostic("IMPLICIT_DIALOGUE_END", "WARNING",
                            "Dialogue node has no explicit transfer or end-dialogue command",
                            entry, identity(entry, entry.key()), null, null);
            }
            for (var transfer : transfers.entrySet()) {
                for (String target : transfer.getValue()) {
                    YamlDocumentNode destination = entries.children().stream().filter(item -> item.key().equals(target)).findFirst().orElse(null);
                    YamlDocumentNode transferStep = findDialogueTransfer(entries.children().stream()
                            .filter(item -> item.key().equals(transfer.getKey())).findFirst().orElseThrow(), target);
                    boolean resolved = destination != null;
                    String targetId = resolved ? identity(destination, destination.key()) : missingNode("dialogue", target);
                    boolean cyclic = pathExists(transfers, target, transfer.getKey(), new HashSet<>());
                    String transferSource = transferStep == null
                            ? identity(entries.children().stream().filter(item -> item.key().equals(transfer.getKey())).findFirst().orElseThrow(), transfer.getKey())
                            : identity(transferStep, semanticId(transferStep));
                    connect(transferSource,
                            targetId, "dialogue-flow", "transfer",
                            transferStep == null ? "/nodes/" + escape(transfer.getKey()) : transferStep.path(),
                            resolved ? destination.path() : null, resolved, cyclic, edges.size());
                    if (!resolved) diagnostic("MISSING_DESTINATION", "ERROR", "Dialogue destination " + target + " does not exist",
                            entries, targetId, "dialogue", target);
                    if (cyclic) diagnostic("TRANSFER_CYCLE", "WARNING", "Dialogue transfer participates in a cycle",
                            destination == null ? entries : destination, targetId, "dialogue", target);
                }
            }
            Set<String> reachable = reachable(transfers, start);
            for (YamlDocumentNode entry : entries.children())
                if (!entry.key().equals(start) && !reachable.contains(entry.key()))
                    diagnostic("UNREACHABLE_NODE", "WARNING", "Dialogue node is unreachable from start",
                            entry, identity(entry, entry.key()), null, null);
        }

        private void quest(YamlDocumentNode root) {
            YamlDocumentNode phases = child(root, "phases");
            if (phases == null || !"sequence".equals(phases.kind())) {
                diagnostic("MISSING_PHASES", "ERROR", "Quest has no phase sequence", root, null, null, null); return;
            }
            String questCenter = identity(root, request.resourceId());
            addWithId(questCenter, root, "quest", request.resourceId(), value(root, "title", "Quest"),
                    List.of(pin(questCenter, "output", "quest-phase-flow", "single", true, "entry", root.path())),
                    List.of(), false, null);
            YamlDocumentNode when = child(root, "when");
            if (when != null) questCondition(questCenter, when, "quest requirement");
            YamlDocumentNode requirements = child(root, "requirements");
            if (requirements != null) for (YamlDocumentNode requirement : requirements.children())
                questCondition(questCenter, requirement, "requirement");
            for (String hook : List.of("on-start", "on-complete", "on-fail", "on-reset")) {
                YamlDocumentNode steps = child(root, hook);
                if (steps != null) projectSteps(steps, questCenter, "quest", new HashMap<>(), hook, hook);
            }
            Map<String, YamlDocumentNode> byId = new LinkedHashMap<>();
            Set<String> duplicateIds = new HashSet<>();
            for (YamlDocumentNode phase : phases.children()) {
                String phaseId = value(phase, "id", phase.key());
                String id = identity(phase, phaseId);
                List<String> badges = new ArrayList<>();
                if (byId.putIfAbsent(phaseId, phase) != null) {
                    badges.add("duplicate id"); duplicateIds.add(phaseId);
                    diagnostic("DUPLICATE_PHASE_ID", "ERROR", "Quest phase ID " + phaseId + " is duplicated",
                            phase, id, "phase", phaseId);
                }
                addWithId(id, phase, "quest-phase", phaseId, "Quest phase",
                        List.of(pin(id, "input", "quest-phase-flow", "many", false, "entry", phase.path()),
                                pin(id, "output", "quest-phase-flow", "many", false, "default next", phase.path()),
                                pin(id, "output", "quest-objective", "many", false, "objectives", phase.path() + "/objectives")),
                        badges, false, null);
                YamlDocumentNode objectives = child(phase, "objectives");
                if (objectives != null) for (YamlDocumentNode objective : objectives.children()) {
                    String objectiveId = identity(objective, value(objective, "id", objective.key()));
                    String type = value(objective, "type", "objective");
                    if (type.contains(":") && !signed("objective", type)) {
                        addCustom(objective, "Custom YAML · unsigned extension " + type); continue;
                    }
                    addWithId(objectiveId, objective, type.contains(":") ? "extension-objective" : "quest-objective",
                            value(objective, "id", type), type,
                            List.of(pin(objectiveId, "input", "quest-objective", "single", true, "phase", objective.path())),
                            type.contains(":") ? List.of("extension") : List.of(), false,
                            type.contains(":") ? type.substring(0, type.indexOf(':')) : null);
                    connect(id, objectiveId, "quest-objective", "objective", phase.path(), objective.path(), true, false, edges.size());
                }
                YamlDocumentNode phaseWhen = child(phase, "when");
                if (phaseWhen != null) questCondition(id, phaseWhen, "phase requirement");
                for (String hook : List.of("on-start", "on-complete", "on-fail", "on-reset")) {
                    YamlDocumentNode steps = child(phase, hook);
                    if (steps != null) projectSteps(steps, id, "quest", new HashMap<>(), hook, hook);
                }
            }
            String terminal = syntheticNode("quest-completion", "Quest complete", "Terminal");
            List<YamlDocumentNode> list = phases.children();
            if (!list.isEmpty()) connect(questCenter,
                    identity(list.getFirst(), value(list.getFirst(), "id", list.getFirst().key())),
                    "quest-phase-flow", "entry", root.path(), list.getFirst().path(), true, false, edges.size());
            Map<String, List<String>> transitions = new LinkedHashMap<>();
            for (int index = 0; index < list.size(); index++) {
                YamlDocumentNode phase = list.get(index);
                String source = value(phase, "id", phase.key());
                List<String> targets = transitions.computeIfAbsent(source, ignored -> new ArrayList<>());
                YamlDocumentNode branches = child(phase, "branches");
                if (branches != null && !branches.children().isEmpty()) {
                    boolean unconditionalSeen = false;
                    for (YamlDocumentNode branch : branches.children()) {
                        if (unconditionalSeen)
                            diagnostic("IMPOSSIBLE_BRANCH", "WARNING",
                                    "This branch can never run because an earlier unconditional branch always matches",
                                    branch, identity(phase, source), null, null);
                        targets.add(value(branch, "next-phase", ""));
                        if (child(branch, "when") == null) unconditionalSeen = true;
                    }
                } else targets.add(index + 1 < list.size() ? value(list.get(index + 1), "id", list.get(index + 1).key()) : "end");
            }
            for (int index = 0; index < list.size(); index++) {
                YamlDocumentNode phase = list.get(index);
                String sourceId = identity(phase, value(phase, "id", phase.key()));
                YamlDocumentNode branches = child(phase, "branches");
                if (branches != null && !branches.children().isEmpty()) {
                    for (YamlDocumentNode branch : branches.children()) {
                        String target = value(branch, "next-phase", "");
                        target = target.equalsIgnoreCase("end") ? "end" : target;
                        YamlDocumentNode destination = byId.get(target);
                        String targetId = target.equals("end") ? terminal
                                : destination == null ? missingNode("phase", target) : identity(destination, target);
                        boolean cyclic = destination != null && pathExists(transitions, target,
                                value(phase, "id", phase.key()), new HashSet<>());
                        String branchId = identity(branch, semanticId(branch));
                        addWithId(branchId, branch, "quest-branch", "Branch " + (branch.key() == null ? "" : branch.key()),
                                child(branch, "when") == null ? "Unconditional" : "Conditional",
                                List.of(pin(branchId, "input", "condition", "single", true, "phase", branch.path()),
                                        pin(branchId, "output", "quest-phase-flow", "single", true, "next phase", branch.path())),
                                List.of(), false, null);
                        connect(sourceId, branchId, "condition", child(branch, "when") == null ? "otherwise" : "when",
                                phase.path(), branch.path(), true, false, edges.size());
                        connect(branchId, targetId, "quest-phase-flow", "next phase", branch.path(),
                                destination == null ? null : destination.path(), target.equals("end") || destination != null,
                                cyclic, edges.size());
                        if (!target.equals("end") && destination == null)
                            diagnostic("MISSING_PHASE", "ERROR", "Quest phase destination " + target + " does not exist",
                                    branch, sourceId, "phase", target);
                    }
                } else {
                    String targetId = index + 1 < list.size()
                            ? identity(list.get(index + 1), value(list.get(index + 1), "id", list.get(index + 1).key())) : terminal;
                    connect(sourceId, targetId, "quest-phase-flow", index + 1 < list.size() ? "default next" : "complete",
                            phase.path(), index + 1 < list.size() ? list.get(index + 1).path() : null, true, false, edges.size());
                }
            }
            if (!list.isEmpty()) {
                String start = value(list.getFirst(), "id", list.getFirst().key());
                Set<String> reachable = reachable(transitions, start);
                for (YamlDocumentNode phase : list) {
                    String phaseId = value(phase, "id", phase.key());
                    String nodeId = identity(phase, phaseId);
                    if (!phaseId.equals(start) && !reachable.contains(phaseId))
                        diagnostic("UNREACHABLE_PHASE", "WARNING", "Quest phase is unreachable from the entry phase",
                                phase, nodeId, "phase", phaseId);
                    if (!duplicateIds.contains(phaseId) && transitions.getOrDefault(phaseId, List.of()).stream()
                            .anyMatch(next -> !next.equals("end")
                                    && pathExists(transitions, next, phaseId, new HashSet<>())))
                        diagnostic("PHASE_CYCLE", "WARNING", "Quest phase participates in a transition cycle",
                                phase, nodeId, "phase", phaseId);
                }
            }
        }

        private void questCondition(String ownerId, YamlDocumentNode condition, String label) {
            String id = identity(condition, label + "-" + condition.path());
            addWithId(id, condition, "quest-condition", label, value(condition, "type", "condition"),
                    List.of(pin(id, "input", "condition", "single", true, "owner", condition.path())),
                    List.of(), false, null);
            connect(ownerId, id, "condition", label, condition.path(), condition.path(), true, false, edges.size());
        }

        private void npc(YamlDocumentNode root) {
            String center = identity(root, request.resourceId());
            addWithId(center, root, "npc", request.resourceId(), value(root, "display-name", "NPC"),
                    List.of(),
                    List.of(), false, null);
            YamlDocumentNode presentation = child(root, "presentation");
            if (presentation != null) appendFields(center, fields(presentation, false));
            for (String key : List.of("shared-behavior", "player-behavior")) {
                YamlDocumentNode reference = child(root, key);
                if (reference != null) npcReference(center, reference, "behavior", reference.value(), key);
            }
            YamlDocumentNode dialogues = child(root, "dialogues");
            if (dialogues != null) for (YamlDocumentNode registration : dialogues.children()) {
                YamlDocumentNode reference = child(registration, "id");
                if (reference != null) npcReference(center, reference, "dialogue", reference.value(), "dialogue");
            }
            YamlDocumentNode anchors = child(root, "anchors");
            if (anchors != null) for (YamlDocumentNode anchor : anchors.children()) {
                String id = identity(anchor, anchor.key());
                addWithId(id, anchor, "npc-anchor", anchor.key(), "World anchor",
                        List.of(pin(id, "input", "anchor", "single", true, "NPC", anchor.path())),
                        List.of(), false, null);
                connect(center, id, "anchor", "anchor", root.path(), anchor.path(), true, false, edges.size());
            }
            for (String hook : List.of("on-interact", "on-no-dialogue")) {
                YamlDocumentNode steps = child(root, hook);
                if (steps != null) projectSteps(steps, center, "script", new HashMap<>(), hook, hook);
            }
        }

        private void npcReference(String center, YamlDocumentNode reference, String targetKind, String targetId, String label) {
            boolean resolved = referenceGraph.declarations().stream()
                    .anyMatch(item -> item.type().equals(targetKind) && item.id().equals(targetId));
            String id = identity(reference, label + ":" + targetId);
            addWithId(id, reference, "resource-reference", targetId, targetKind,
                    List.of(pin(id, "input", "reference:" + targetKind, "single", true, label, reference.path()),
                            pin(id, "output", "reference:" + targetKind, "single", false, "open", reference.path())),
                    resolved ? List.of() : List.of("unresolved"), false, null);
            connect(center, id, "reference:" + targetKind, label, request.yamlPath(), reference.path(), resolved, false, edges.size());
        }

        private void script(YamlDocumentNode root) {
            if(!"mapping".equals(root.kind())||child(root,"nodes")==null||child(root,"connections")==null)
                throw error(HttpStatus.UNPROCESSABLE_ENTITY,"SCRIPT_FORMAT_MIGRATION_REQUIRED",
                        "Reusable scripts must use scripts.yml content-version 2 descriptors",request.path(),root.path());
            String inputId=request.resourceKind()+":"+request.resourceId()+"#input",outputId=request.resourceKind()+":"+request.resourceId()+"#output";
            Map<String,GraphPin> endpointPins=new LinkedHashMap<>();
            List<GraphPin> inputPins=new ArrayList<>(),outputPins=new ArrayList<>();
            GraphPin inputExec=typedPin(inputId,"output","EXECUTION","execution",false,"exec",root.path(),0,null,null);
            GraphPin outputExec=typedPin(outputId,"input","EXECUTION","execution",true,"exec",root.path(),0,null,null);
            inputPins.add(inputExec);outputPins.add(outputExec);endpointPins.put("$input.exec",inputExec);endpointPins.put("$output.exec",outputExec);
            YamlDocumentNode inputs=child(root,"inputs"),outputs=child(root,"outputs");int order=1;
            if(inputs!=null)for(YamlDocumentNode parameter:inputs.children()){String type=scriptValueType(value(parameter,"type","string"),parameter);boolean required=Boolean.parseBoolean(value(parameter,"required","false"));GraphPin pin=typedPin(inputId,"output","DATA",type,required,parameter.key(),parameter.path(),order++,childValue(parameter,"default"),childValue(parameter,"default"));inputPins.add(pin);endpointPins.put("$input."+parameter.key(),pin);}
            order=1;if(outputs!=null)for(YamlDocumentNode parameter:outputs.children()){String type=scriptValueType(value(parameter,"type","string"),parameter);boolean required=Boolean.parseBoolean(value(parameter,"required","false"));GraphPin pin=typedPin(outputId,"input","DATA",type,required,parameter.key(),parameter.path(),order++,childValue(parameter,"default"),childValue(parameter,"default"));outputPins.add(pin);endpointPins.put("$output."+parameter.key(),pin);}
            addWithId(inputId,inputs==null?root:inputs,"script-input","Input","Execution entry and typed parameters",inputPins,List.of("boundary","non-deletable"),false,null);
            addWithId(outputId,outputs==null?root:outputs,"script-output","Output","Execution result and typed returns",outputPins,List.of("boundary","non-deletable"),false,null);
            Map<String,String> nodeIds=new LinkedHashMap<>();YamlDocumentNode definitions=child(root,"nodes");
            for(YamlDocumentNode definition:definitions.children()){
                String nodeKey=definition.key(),type=value(definition,"type","unknown"),nodeId=request.resourceKind()+":"+request.resourceId()+"#node:"+pinToken(nodeKey);nodeIds.put(nodeKey,nodeId);List<GraphPin> pins=new ArrayList<>();
                if(type.equals("value")){String valueType=scriptValueType(value(definition,"value-type","string"),definition);GraphPin pin=typedPin(nodeId,"output","DATA",valueType,false,"value",definition.path()+"/value",0,childValue(definition,"value"),null);pins.add(pin);endpointPins.put(nodeKey+".value",pin);}
                else if(Set.of("integer-to-number","string-to-text","to-string").contains(type)){String source=type.equals("integer-to-number")?"integer":type.equals("string-to-text")?"string":scriptValueType(value(definition,"value-type","string"),definition);String target=type.equals("integer-to-number")?"number":type.equals("string-to-text")?"text":"string";YamlDocumentNode literal=child(definition,"value");GraphPin in=typedPin(nodeId,"input","DATA",source,true,"value",literal==null?definition.path()+"/value":literal.path(),0,literal==null?null:literal.value(),null),out=typedPin(nodeId,"output","DATA",target,false,"result",definition.path(),0,null,null);pins.add(in);pins.add(out);endpointPins.put(nodeKey+".value",in);endpointPins.put(nodeKey+".result",out);}
                else {GraphPin exec=typedPin(nodeId,"input","EXECUTION","execution",true,"exec",definition.path(),0,null,null),success=typedPin(nodeId,"output","EXECUTION","execution",false,"success",definition.path(),0,null,null),failure=typedPin(nodeId,"output","EXECUTION","execution",false,"failure",definition.path(),1,null,null);pins.add(exec);pins.add(success);pins.add(failure);endpointPins.put(nodeKey+".exec",exec);endpointPins.put(nodeKey+".success",success);endpointPins.put(nodeKey+".failure",failure);int dataOrder=0;if(type.equals("run-script")){String targetId=value(definition,"script","");YamlDocumentNode scripts=child(documentRoot,"scripts"),target=scripts==null?null:child(scripts,targetId),arguments=child(definition,"inputs");if(target==null){diagnostic("MISSING_SCRIPT","ERROR","Run-script target "+targetId+" does not exist",definition,nodeId,"script",targetId);}else{YamlDocumentNode targetInputs=child(target,"inputs"),targetOutputs=child(target,"outputs");if(targetInputs!=null)for(YamlDocumentNode parameter:targetInputs.children()){String parameterType=scriptValueType(value(parameter,"type","string"),parameter);boolean required=Boolean.parseBoolean(value(parameter,"required","false"));YamlDocumentNode literal=arguments==null?null:child(arguments,parameter.key());String path=literal==null?definition.path()+"/inputs/"+parameter.key():literal.path();GraphPin pin=typedPin(nodeId,"input","DATA",parameterType,required,parameter.key(),path,dataOrder++,literal==null?null:literal.value(),childValue(parameter,"default"));pins.add(pin);endpointPins.put(nodeKey+"."+parameter.key(),pin);}if(targetOutputs!=null)for(YamlDocumentNode parameter:targetOutputs.children()){String parameterType=scriptValueType(value(parameter,"type","string"),parameter);GraphPin pin=typedPin(nodeId,"output","DATA",parameterType,false,parameter.key(),definition.path(),dataOrder++,null,null);pins.add(pin);endpointPins.put(nodeKey+"."+parameter.key(),pin);}}}else if(type.contains(":")&&signed("command",type))extensionCommandPins(definition,nodeId,nodeKey,type,pins,endpointPins);else for(YamlDocumentNode field:definition.children())if(!Set.of("type","value-type").contains(field.key())){String valueType=scriptFieldType(type,field.key());GraphPin pin=typedPin(nodeId,"input","DATA",valueType,requiredScriptField(type,field.key()),field.key(),field.path(),dataOrder++,field.value(),field.value());pins.add(pin);endpointPins.put(nodeKey+"."+field.key(),pin);}}
                if(!Set.of("value","run-script","integer-to-number","string-to-text","to-string").contains(type)&&!type.contains(":")){int missingOrder=(int)pins.stream().filter(pin->pin.channel().equals("DATA")).count();for(String field:scriptCommandFields(type))if(pins.stream().noneMatch(pin->pin.channel().equals("DATA")&&pin.label().equals(field))){GraphPin pin=typedPin(nodeId,"input","DATA",scriptFieldType(type,field),requiredScriptField(type,field),field,definition.path()+"/"+field,missingOrder++,null,null);pins.add(pin);endpointPins.put(nodeKey+"."+field,pin);}}
                if(Set.of("branch","if").contains(type)){pins.removeIf(pin->Set.of("success","failure","condition").contains(pin.label()));endpointPins.remove(nodeKey+".success");endpointPins.remove(nodeKey+".failure");endpointPins.remove(nodeKey+".condition");YamlDocumentNode literal=child(definition,"condition");GraphPin yes=typedPin(nodeId,"output","EXECUTION","execution",false,"true",definition.path(),0,null,null),no=typedPin(nodeId,"output","EXECUTION","execution",false,"false",definition.path(),1,null,null),condition=typedPin(nodeId,"input","DATA","boolean",true,"condition",literal==null?definition.path()+"/condition":literal.path(),0,literal==null?null:literal.value(),null);pins.add(yes);pins.add(no);pins.add(condition);endpointPins.put(nodeKey+".true",yes);endpointPins.put(nodeKey+".false",no);endpointPins.put(nodeKey+".condition",condition);}
                if(type.equals("stop")){pins.removeIf(pin->Set.of("success","failure").contains(pin.label()));endpointPins.remove(nodeKey+".success");endpointPins.remove(nodeKey+".failure");}
                addWithId(nodeId,definition,type.equals("value")?"script-value":type.equals("run-script")?"script-call":"script-"+type,nodeKey,type,pins,type.contains(":")?List.of("extension"):List.of(),false,type.contains(":")?type.substring(0,type.indexOf(':')):null);
            }
            YamlDocumentNode connections=child(root,"connections");Set<String> targets=new HashSet<>();for(YamlDocumentNode connection:connections.children()){String from=value(connection,"from",""),to=value(connection,"to","");GraphPin source=endpointPins.get(from),target=endpointPins.get(to);if(source==null||target==null)throw error(HttpStatus.UNPROCESSABLE_ENTITY,"MISSING_PIN_ENDPOINT","Connection "+connection.key()+" references a missing pin",request.path(),connection.path());if(!targets.add(target.id()))throw error(HttpStatus.UNPROCESSABLE_ENTITY,"CARDINALITY_EXCEEDED","Data/execution inputs accept only one connection",request.path(),connection.path());if(!source.channel().equals(target.channel())||source.channel().equals("DATA")&&!source.valueType().equals(target.valueType()))throw error(HttpStatus.UNPROCESSABLE_ENTITY,"INCOMPATIBLE_PIN_TYPES","Connection "+connection.key()+" has incompatible channel or nominal types",request.path(),connection.path());edges.add(new GraphEdge("script-edge:"+pinToken(connection.key()),source.id(),target.id(),source.channel().equals("EXECUTION")?"execution":"data:"+source.valueType(),connection.key(),connection.path()+"/from",connection.path()+"/to",true,false,range(child(connection,"from")),range(child(connection,"to"))));}
        }

        private GraphPin typedPin(String nodeId,String direction,String channel,String valueType,boolean required,String label,String path,int order,String literal,String defaultValue){String id=nodeId+":"+direction.toLowerCase(Locale.ROOT)+":"+pinToken(label);String semantic=channel.equals("EXECUTION")?"execution":"data:"+valueType;return new GraphPin(id,nodeId,direction,semantic,required?"EXACTLY_ONE":"ZERO_OR_ONE",required,label,path,order,null,new PortCompatibility(List.of(semantic),List.of("CURRENT_RESOURCE"),"DENY",List.of("CONNECT")),channel,valueType,new LiteralMetadata(literal,defaultValue,defaultValue!=null,false,channel.equals("DATA")&&direction.equals("input")),scriptResourceKind(valueType));}
        private void extensionCommandPins(YamlDocumentNode definition,String nodeId,String nodeKey,String type,List<GraphPin> pins,Map<String,GraphPin> endpointPins){EditorSchemaDocument schema=signedSchemas.get("command\0"+type);if(schema==null)return;try{JsonNode root=JSON.readTree(schema.schemaJson());int order=0;for(JsonNode pinNode:root.path("x-persona-input-pins")){String name=pinNode.path("name").asText(),valueType=scriptValueType(pinNode.path("valueType").asText(),definition);boolean required=pinNode.path("required").asBoolean(false);YamlDocumentNode literal=child(definition,name);String defaultValue=pinNode.has("default")?pinNode.path("default").asText():null;GraphPin pin=typedPin(nodeId,"input","DATA",valueType,required,name,literal==null?definition.path()+"/"+name:literal.path(),order++,literal==null?null:literal.value(),defaultValue);pins.add(pin);endpointPins.put(nodeKey+"."+name,pin);}for(JsonNode pinNode:root.path("x-persona-output-pins")){String name=pinNode.path("name").asText(),valueType=scriptValueType(pinNode.path("valueType").asText(),definition);GraphPin pin=typedPin(nodeId,"output","DATA",valueType,false,name,definition.path(),order++,null,null);pins.add(pin);endpointPins.put(nodeKey+"."+name,pin);}}catch(GraphContractException invalid){throw invalid;}catch(Exception invalid){diagnostic("INVALID_EXTENSION_PIN_SCHEMA","ERROR","Signed extension pin metadata is invalid",definition,nodeId,"command",type);}}
        private String childValue(YamlDocumentNode node,String key){YamlDocumentNode child=child(node,key);return child==null?null:child.value();}
        private String scriptValueType(String type,YamlDocumentNode source){if(type.contains(":")&&!signedValueTypes.contains(type))throw error(HttpStatus.UNPROCESSABLE_ENTITY,"UNSIGNED_EXTENSION_VALUE_TYPE","Nominal value type "+type+" is not declared by the signed extension catalog",request.path(),source.path());return type;}
        private String scriptResourceKind(String type){return Set.of("npc","npc-instance","behavior","dialogue","quest","quest-objective","script","anchor","world","material","entity-type","sound","particle").contains(type)?type:null;}
        private String scriptFieldType(String command,String key){if(command.equals("set-flag")&&key.equals("value")||Set.of("ambient","particles").contains(key))return "boolean";return switch(key){case "quest"->"quest";case "objective"->"quest-objective";case "amount","count","amplifier"->"integer";case "volume","pitch","radius","offset-x","offset-y","offset-z","extra"->"number";case "duration","fade-in","stay","fade-out"->"duration";case "location"->"location";case "material"->"material";case "entity"->"entity-type";case "sound"->"sound";case "particle"->"particle";case "text","title","subtitle"->"text";case "script"->"script";case "dialogue"->"dialogue";case "behavior"->"behavior";case "npc"->"npc";case "world"->"world";case "anchor"->"anchor";default->"string";};}
        private boolean requiredScriptField(String command,String field){return switch(command){case "say"->field.equals("text");case "wait"->field.equals("duration");case "start-quest","finish-quest"->field.equals("quest");case "deliver-items"->Set.of("quest","objective").contains(field);case "give-item","take-item","set-block"->field.equals("material");case "message","action-bar","broadcast","npc-speak"->field.equals("text");case "play-sound"->field.equals("sound");case "particle"->field.equals("particle");case "run-command"->field.equals("command");case "spawn-entity"->field.equals("entity");default->false;};}
        private Set<String> scriptCommandFields(String type){return switch(type){case "say"->Set.of("text","delay");case "wait"->Set.of("duration");case "start-quest","finish-quest"->Set.of("quest");case "deliver-items"->Set.of("quest","objective");case "set-flag"->Set.of("flag","value");case "set-variable"->Set.of("variable","name","value","operation");case "message","action-bar","broadcast","npc-speak"->Set.of("text","audience","radius","location");case "title"->Set.of("title","subtitle","fade-in","stay","fade-out","audience","radius","location");case "play-sound"->Set.of("sound","volume","pitch","audience","radius","location");case "particle"->Set.of("particle","count","offset-x","offset-y","offset-z","extra","audience","radius","location");case "give-item","take-item"->Set.of("material","amount");case "give-experience"->Set.of("amount");case "run-command"->Set.of("command","as");case "teleport","lightning-effect","npc-move"->Set.of("location");case "potion-effect"->Set.of("effect","duration","amplifier","ambient","particles");case "spawn-entity"->Set.of("entity","location");case "set-block"->Set.of("material","location");case "npc-animation"->Set.of("animation");default->Set.of();};}

        private void other(YamlDocumentNode root) {
            addCustom(root, "Custom YAML");
        }

        private void projectSteps(YamlDocumentNode sequence, String ownerId, String context,
                                  Map<String, List<String>> transfers, String transferOwner, String firstLabel) {
            if (!"sequence".equals(sequence.kind())) { addCustom(sequence, "Custom script YAML"); return; }
            String previous = ownerId;
            for (int index = 0; index < sequence.children().size(); index++) {
                YamlDocumentNode step = sequence.children().get(index);
                if (!"mapping".equals(step.kind())) { addCustom(step, "Custom script step"); continue; }
                String type = value(step, "type", "unknown");
                if (type.contains(":") && !signed("command", type)) {
                    addCustom(step, "Custom YAML · unsigned extension " + type); previous = null; continue;
                }
                String id = identity(step, semanticId(step));
                List<String> badges = type.contains(":") ? List.of("extension") : List.of();
                List<GraphPin> stepPins = new ArrayList<>();
                stepPins.add(pin(id, "input", "execution", "single", true, "in", step.path()));
                if (type.equals("if")) {
                    stepPins.add(pin(id, "output", "execution", "single", false, "true", step.path() + "/then"));
                    stepPins.add(pin(id, "output", "execution", "single", false, "false", step.path() + "/else"));
                } else if (!Set.of("stop", "end-dialogue", "goto").contains(type))
                    stepPins.add(pin(id, "output", "execution", "single", false, "next", step.path()));
                if (type.equals("goto")) stepPins.add(pin(id, "output",
                        child(step, "dialogue") == null ? "dialogue-flow" : "dialogue-transfer-reference",
                        "single", true, "transfer", step.path()));
                if (type.contains(":")) extensionCommandPins(step, id, "extension", type,
                        stepPins, new HashMap<>());
                else {
                    int dataOrder = 0;
                    for (YamlDocumentNode field : step.children())
                        if (field.children().isEmpty() && !field.key().equals("type")) {
                            String valueType = scriptFieldType(type, field.key());
                            stepPins.add(typedPin(id, "input", "DATA", valueType, requiredScriptField(type, field.key()), field.key(),
                                    field.path(), dataOrder++, field.value(), field.value()));
                        }
                }
                addWithId(id, step, type.contains(":") ? "extension-command" : "script-" + type,
                        titleForStep(step, type), type,
                        stepPins,
                        badges, false, type.contains(":") ? type.substring(0, type.indexOf(':')) : null);
                for (GraphPin dataPin : stepPins)
                    if ("INPUT".equals(dataPin.direction()) && "DATA".equals(dataPin.channel())
                            && dataPin.resourceKind() != null && dataPin.literal().value() != null)
                        resourceValueBinding(dataPin);
                if (previous != null) connect(previous, id, "execution", index == 0 && firstLabel != null ? firstLabel : "next",
                        sequence.path(), step.path(), true, false, index);
                else diagnostic("UNREACHABLE_SCRIPT_STEP", "WARNING",
                        "This step follows a terminal command and is unreachable", step, id, null, null);
                previous = Set.of("stop", "end-dialogue", "goto").contains(type) ? null : id;
                if (type.equals("goto") && child(step, "dialogue") == null) {
                    String target = value(step, "node", "");
                    if (!target.isBlank()) transfers.computeIfAbsent(transferOwner, ignored -> new ArrayList<>()).add(target);
                }
                for (String nested : List.of("then", "else", "script", "on-success", "on-failure")) {
                    YamlDocumentNode nestedSteps = child(step, nested);
                    if (nestedSteps != null && "sequence".equals(nestedSteps.kind()))
                        projectSteps(nestedSteps, id, context, transfers, transferOwner, nested.replace('-', ' '));
                }
                YamlDocumentNode options = child(step, "options");
                if (options != null) for (YamlDocumentNode option : options.children()) {
                    YamlDocumentNode nestedSteps = child(option, "script");
                    if (nestedSteps != null) projectSteps(nestedSteps, id, context, transfers, transferOwner,
                            value(option, "text", "choice"));
                }
            }
        }

        private void scriptReference(String ownerId, YamlDocumentNode reference) {
            if (reference == null || reference.value() == null) return;
            String targetId = reference.value();
            boolean resolved = referenceGraph.declarations().stream()
                    .anyMatch(item -> item.type().equals("script") && item.id().equals(targetId));
            String id = identity(reference, "script:" + targetId);
            addWithId(id, reference, "resource-reference", targetId, "script",
                    List.of(pin(id, "input", "script-reference", "single", true, "script", reference.path()),
                            pin(id, "output", "script-reference", "single", false, "open", reference.path())),
                    resolved ? List.of() : List.of("unresolved"), false, null);
            connect(ownerId, id, "script-reference", "script", reference.path(), reference.path(), resolved, false, edges.size());
        }

        private void resourceValueBinding(GraphPin target) {
            YamlDocumentNode scalar = find(documentRoot, target.yamlPath());
            if (scalar == null) return;
            String id = request.resourceKind() + ":" + request.resourceId() + "#value:"
                    + sha256(target.yamlPath()).substring(0, 12);
            GraphPin source = typedPin(id, "output", "DATA", target.valueType(), false,
                    "value", scalar.path(), 0, scalar.value(), null);
            addWithId(id, scalar, "resource-value", scalar.value(), target.resourceKind(),
                    List.of(source), List.of("pure", "resource"), false, null);
            String edgeId = "resource-binding:" + sha256(target.yamlPath()).substring(0, 16);
            if (edgeIds.add(edgeId)) edges.add(new GraphEdge(edgeId, source.id(), target.id(),
                    "data:" + target.valueType(), target.label(), scalar.path(), target.yamlPath(),
                    true, false, range(scalar), target.sourceRange()));
        }

        private void customFallback(YamlDocumentNode root) {
            Set<String> known = switch (request.resourceKind()) {
                case "behavior" -> BEHAVIOR_ROOT;
                case "dialogue" -> DIALOGUE_ROOT;
                case "quest" -> QUEST_ROOT;
                case "npc" -> NPC_ROOT;
                case "script" -> Set.of();
                default -> Set.of();
            };
            if ("mapping".equals(root.kind())) for (YamlDocumentNode child : root.children())
                if (!known.contains(child.key()) && !represented.contains(child.path()))
                    addCustom(child, "Custom YAML · " + child.key());
            visit(root, node -> {
                if ((node.kind().equals("custom") || node.kind().equals("alias")) && !represented.contains(node.path()))
                    addCustom(node, node.kind().equals("alias") ? "YAML alias" : "Tagged custom YAML");
            });
        }

        private void referenceDiagnostics() {
            for (ProjectReference reference : referenceGraph.references()) {
                if (!reference.path().equals(request.path()) || reference.resolved()) continue;
                YamlDocumentNode location = null;
                diagnostic("UNRESOLVED_REFERENCE", "ERROR",
                        "Missing " + reference.targetType() + " " + reference.targetId(),
                        location, nodeByPath.get(reference.yamlPath()), reference.targetType(), reference.targetId(),
                        reference.yamlPath());
            }
        }

        private String add(YamlDocumentNode source, String kind, String title, String subtitle,
                           List<GraphPin> pins, List<String> badges, boolean custom) {
            String id = identity(source, semanticId(source));
            addWithId(id, source, kind, title, subtitle, pins, badges, custom, null); return id;
        }

        private void addWithId(String id, YamlDocumentNode source, String kind, String title, String subtitle,
                               List<GraphPin> pins, List<String> badges, boolean custom, String owner) {
            if (nodes.size() >= MAX_NODES) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "GRAPH_NODE_LIMIT",
                    "Graph exceeds 10000 nodes", request.path(), source.path());
            if (nodeByPath.containsKey(source.path()) && !custom) return;
            List<GraphField> fields = fields(source, custom);
            nodes.add(new GraphNode(id, source.path(), range(source), kind, title, subtitle, fields, pins,
                    badges, custom, owner));
            nodeIndexById.put(id, nodes.size() - 1);
            nodeByPath.putIfAbsent(source.path(), id); represented.add(source.path());
        }

        private void addCustom(YamlDocumentNode source, String title) {
            if (represented.contains(source.path())) return;
            String id = identity(source, "custom");
            addWithId(id, source, "custom-yaml", title, source.tag(),
                    List.of(),
                    List.of("custom data"), true, null);
        }

        private void appendFields(String nodeId, List<GraphField> additional) {
            Integer index = nodeIndexById.get(nodeId); if (index == null || additional.isEmpty()) return;
            GraphNode node = nodes.get(index); List<GraphField> values = new ArrayList<>(node.fields()); values.addAll(additional);
            nodes.set(index, new GraphNode(node.id(), node.yamlPath(), node.range(), node.kind(), node.title(),
                    node.subtitle(), List.copyOf(values), node.pins(), node.badges(), node.custom(), node.extensionOwner()));
        }

        private String syntheticNode(String kind, String title, String subtitle) {
            String id = request.resourceKind() + ":" + request.resourceId() + "#synthetic:" + kind;
            SourceRange range = new SourceRange(0, 0, 1, 1, 1, 1);
            nodes.add(new GraphNode(id, "", range, kind, title, subtitle, List.of(),
                    List.of(pin(id, "input", "quest-phase-flow", "many", false, "complete", "")),
                    List.of(), false, null));
            nodeIndexById.put(id, nodes.size() - 1);
            return id;
        }

        private String missingNode(String kind, String target) {
            String id = request.resourceKind() + ":" + request.resourceId() + "#missing:" + kind + ":" + target;
            if (nodes.stream().noneMatch(node -> node.id().equals(id)))
                nodes.add(new GraphNode(id, "", new SourceRange(0, 0, 1, 1, 1, 1), "missing-reference",
                        target, "Missing " + kind, List.of(),
                        List.of(pin(id, "input", kind.equals("phase") ? "quest-phase-flow"
                                : kind.equals("dialogue") ? "dialogue-flow" : "reference:" + kind,
                                "many", true, "missing", "")),
                        List.of("unresolved"), false, null));
            nodeIndexById.putIfAbsent(id, nodes.size() - 1);
            return id;
        }

        private void connect(String sourceNode, String targetNode, String type, String label,
                             String sourcePath, String targetPath, boolean resolved, boolean cyclic, int ordinal) {
            if (edges.size() >= MAX_EDGES) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "GRAPH_EDGE_LIMIT",
                    "Graph exceeds 20000 edges", request.path(), sourcePath);
            String sourcePin = type.equals("behavior-child")
                    ? sourceNode + ":out:child:" + pinToken(targetNode)
                    : nodes.stream().filter(node -> node.id().equals(sourceNode)).findFirst()
                    .flatMap(node -> node.pins().stream().filter(pin -> pin.direction().equals("OUTPUT")
                            && pin.semanticType().equals(type) && pin.label().equals(label)
                            && edges.stream().noneMatch(edge -> edge.sourcePinId().equals(pin.id()))).findFirst())
                    .map(GraphPin::id).orElse(sourceNode + ":out:link:" + pinToken(targetNode));
            String targetPin = targetNode + ":in";
            ensurePin(sourceNode, sourcePin, "output", type, "many", false, label, sourcePath, ordinal);
            ensurePin(targetNode, "input", type, "many", false, "in", targetPath);
            String edgeId = sourcePin + "->" + targetPin;
            if (!edgeIds.add(edgeId)) throw error(HttpStatus.UNPROCESSABLE_ENTITY,
                    "DUPLICATE_EDGE", "Projection contains a duplicate typed connection", request.path(), sourcePath);
            edges.add(new GraphEdge(edgeId, sourcePin, targetPin, type, label, sourcePath, targetPath, resolved, cyclic));
        }

        private void ensurePin(String nodeId, String direction, String type, String cardinality,
                               boolean required, String label, String path) {
            String id = direction.equals("input") ? nodeId + ":in" : nodeId + ":out:" + pinToken(label);
            ensurePin(nodeId, id, direction, type, cardinality, required, label, path, null);
        }

        private void ensurePin(String nodeId, String id, String direction, String type, String cardinality,
                               boolean required, String label, String path, Integer order) {
            Integer index = nodeIndexById.get(nodeId);
            if (index == null) return;
            GraphNode node = nodes.get(index);
            if (node.pins().stream().anyMatch(pin -> pin.id().equals(id))) return;
            List<GraphPin> pins = new ArrayList<>(node.pins());
            SourceRange sourceRange = nodes.stream().filter(candidate -> Objects.equals(candidate.yamlPath(), path))
                    .map(GraphNode::range).findFirst().orElseGet(() -> nodes.stream()
                            .flatMap(candidate -> candidate.fields().stream())
                            .filter(field -> Objects.equals(field.yamlPath(), path)).map(GraphField::range)
                            .findFirst().orElse(null));
            pins.add(new GraphPin(id, nodeId, direction, type, cardinality, required, label, path,
                    order, sourceRange, new PortCompatibility(List.of(type), List.of("CURRENT_RESOURCE"),
                    type.startsWith("dialogue-") ? "ALLOW" : "DENY", List.of("CONNECT"))));
            nodes.set(index, new GraphNode(node.id(), node.yamlPath(), node.range(), node.kind(), node.title(),
                    node.subtitle(), node.fields(), pins, node.badges(), node.custom(), node.extensionOwner()));
        }

        private void diagnostic(String code, String severity, String message, YamlDocumentNode source,
                                String nodeId, String relatedKind, String relatedId) {
            diagnostic(code, severity, message, source, nodeId, relatedKind, relatedId,
                    source == null ? request.yamlPath() : source.path());
        }

        private void diagnostic(String code, String severity, String message, YamlDocumentNode source,
                                String nodeId, String relatedKind, String relatedId, String yamlPath) {
            diagnostics.add(new GraphDiagnostic(code, severity, message, request.path(), yamlPath,
                    source == null ? new SourceRange(0, 0, 1, 1, 1, 1) : range(source),
                    nodeId, relatedKind, relatedId));
        }

        private EditorGraphProjection finish(String digest) {
            nodes.sort(Comparator.comparingInt(node -> node.range().startOffset()));
            return new EditorGraphProjection(VERSION, request.resourceKind() + ":" + request.resourceId(),
                    request.resourceKind(), request.resourceId(), request.path(), normalizeRoot(request.yamlPath()),
                    digest, true, nodes, edges, diagnostics,
                    List.of("SELECT", "PAN_ZOOM", "AUTO_LAYOUT", "INSPECT", "EDIT_FIELDS",
                            "CREATE_NODE", "DELETE_NODE", "CONNECT", "DISCONNECT", "RECONNECT",
                            "INSERT_ON_WIRE", "REORDER", "COMPOUND", "COPY", "DUPLICATE", "WRAP", "UNWRAP",
                            "EDIT_PIN_DEFAULT","CREATE_VALUE_NODE","REMOVE_VALUE_NODE","EDIT_SCRIPT_SIGNATURE"), schemaCatalogVersion, null,
                    List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        private boolean signed(String contentType, String typeId) {
            return signedSchemaTypes.contains(contentType + "\0" + typeId);
        }

        private List<GraphField> fields(YamlDocumentNode node, boolean custom) {
            if (!"mapping".equals(node.kind())) return node.children().isEmpty()
                    ? List.of(field(node, custom)) : List.of();
            List<GraphField> result = new ArrayList<>();
            for (YamlDocumentNode child : node.children())
                if (child.children().isEmpty()) result.add(field(child, custom || child.kind().equals("custom") || child.kind().equals("alias")));
            return result;
        }

        private GraphField field(YamlDocumentNode node, boolean custom) {
            return new GraphField(identity(node, "field"), node.key() == null ? node.path() : node.key(),
                    node.path(), range(node), node.kind(), node.value(), node.editable(), false, custom);
        }

        private GraphPin pin(String nodeId, String direction, String type, String cardinality,
                             boolean required, String label, String path) {
            String suffix = direction.equals("input") ? "in" : "out:" + pinToken(label);
            return new GraphPin(nodeId + ":" + suffix, nodeId, direction, type, cardinality,
                    required, label, path, null, null, new PortCompatibility(List.of(type),
                    List.of("CURRENT_RESOURCE"), type.startsWith("dialogue-") ? "ALLOW" : "DENY",
                    List.of("CONNECT")));
        }

        private String identity(YamlDocumentNode node, String semantic) {
            return identityByPath.computeIfAbsent(node.path(), ignored -> {
                String stable = semantic == null || semantic.isBlank() ? "content:" + sourceIdentity(node) : semantic;
                String candidate = request.resourceKind() + ":" + request.resourceId() + "#" + pinToken(stable);
                if (!identities.add(candidate)) {
                    candidate += "@" + pinToken(node.path());
                    identities.add(candidate);
                }
                return candidate;
            });
        }

        private String sourceIdentity(YamlDocumentNode node) {
            StringBuilder signature = new StringBuilder();
            structuralSignature(node, signature, 0);
            return sha256(signature.toString()).substring(0, 16);
        }

        private static void structuralSignature(YamlDocumentNode node, StringBuilder result, int depth) {
            if (node == null || depth > 16) return;
            String key = node.key() != null && (node.key().matches("\\d+") || node.key().matches("\\[\\d+]")
                    || node.key().matches(".*/\\d+"))
                    ? null : node.key();
            result.append('(').append(key).append(':').append(node.kind());
            if ("mapping".equals(node.kind())) {
                YamlDocumentNode type = child(node, "type");
                if (type != null) result.append(":type=").append(type.value());
                for (YamlDocumentNode child : node.children())
                    if (!child.children().isEmpty()) structuralSignature(child, result, depth + 1);
                    else result.append(':').append(child.key()).append('/').append(child.kind());
            } else if ("sequence".equals(node.kind())) {
                for (YamlDocumentNode child : node.children()) structuralSignature(child, result, depth + 1);
            }
            result.append(')');
        }

        private static String titleForStep(YamlDocumentNode step, String type) {
            if (type.equals("say")) return value(step, "text", "Say");
            if (type.equals("goto")) return "Go to " + value(step, "node", value(step, "dialogue", "destination"));
            if (type.equals("choice")) return "Player choice";
            return type;
        }

        private static List<YamlDocumentNode> behaviorChildren(YamlDocumentNode node) {
            YamlDocumentNode many = child(node, "children");
            if (many != null && "sequence".equals(many.kind())) return many.children();
            YamlDocumentNode one = child(node, "child");
            return one == null ? List.of() : List.of(one);
        }

        private static String semanticId(YamlDocumentNode node) { return value(node, "id", null); }
        private static YamlDocumentNode findDialogueTransfer(YamlDocumentNode node, String target) {
            if ("mapping".equals(node.kind()) && "goto".equals(value(node, "type", ""))
                    && target.equals(value(node, "node", "")) && child(node, "dialogue") == null) return node;
            for (YamlDocumentNode child : node.children()) {
                YamlDocumentNode found = findDialogueTransfer(child, target); if (found != null) return found;
            }
            return null;
        }
        private static boolean containsStepType(YamlDocumentNode node, Set<String> types) {
            if ("mapping".equals(node.kind()) && types.contains(value(node, "type", ""))) return true;
            return node.children().stream().anyMatch(child -> containsStepType(child, types));
        }
        private static String value(YamlDocumentNode node, String key, String fallback) {
            YamlDocumentNode child = child(node, key); return child == null || child.value() == null ? fallback : child.value();
        }
        private static YamlDocumentNode child(YamlDocumentNode node, String key) {
            if (node == null) return null;
            return node.children().stream().filter(item -> Objects.equals(key, item.key())).findFirst().orElse(null);
        }
        private static SourceRange range(YamlDocumentNode node) {
            return new SourceRange(node.startOffset(), node.endOffset(), node.startLine(), node.startColumn(),
                    node.endLine(), node.endColumn());
        }
        private static String escape(String value) { return value.replace("~", "~0").replace("/", "~1"); }
        private static String pinToken(String value) { return (value == null ? "" : value).replaceAll("[^A-Za-z0-9_.:-]", "_"); }
        private static void visit(YamlDocumentNode node, java.util.function.Consumer<YamlDocumentNode> action) {
            if (node == null) return; action.accept(node); node.children().forEach(child -> visit(child, action));
        }
        private static boolean pathExists(Map<String, List<String>> graph, String current, String target, Set<String> seen) {
            if (Objects.equals(current, target)) return true;
            if (!seen.add(current)) return false;
            return graph.getOrDefault(current, List.of()).stream().anyMatch(next -> pathExists(graph, next, target, seen));
        }
        private static Set<String> reachable(Map<String, List<String>> graph, String start) {
            Set<String> result = new HashSet<>(); ArrayDeque<String> queue = new ArrayDeque<>();
            if (start != null) queue.add(start);
            while (!queue.isEmpty()) { String value = queue.remove(); if (!result.add(value)) continue;
                queue.addAll(graph.getOrDefault(value, List.of())); }
            return result;
        }
    }

    private static void requireRequest(GraphProjectionRequest request) {
        if (request == null || request.path() == null || request.content() == null || request.resourceId() == null
                || !KINDS.contains(request.resourceKind()) || request.content().getBytes(StandardCharsets.UTF_8).length > 1_048_576)
            throw error(HttpStatus.BAD_REQUEST, "INVALID_PROJECTION_REQUEST", "Invalid graph projection request",
                    request == null ? null : request.path(), request == null ? null : request.yamlPath());
    }
    private static String normalizeRoot(String value) { return value == null ? "" : value; }
    private static YamlDocumentNode find(YamlDocumentNode node, String path) {
        if (node == null) return null;
        if (node.path().equals(path)) return node;
        for (YamlDocumentNode child : node.children()) { YamlDocumentNode found = find(child, path); if (found != null) return found; }
        return null;
    }
    private static String sha256(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean constantEquals(String left, String right) {
        return right != null && MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }
    private static String relationshipId(String kind, String id) {
        return "relationship:" + kind + ":" + id.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }
    private static boolean relationshipPath(Map<String, List<String>> graph, String current,
                                            String target, Set<String> seen) {
        if (Objects.equals(current, target)) return true;
        if (!seen.add(current)) return false;
        return graph.getOrDefault(current, List.of()).stream()
                .anyMatch(next -> relationshipPath(graph, next, target, new HashSet<>(seen)));
    }
    private static GraphContractException error(HttpStatus status, String code, String message, String file, String path) {
        return new GraphContractException(status, code, message, file, path);
    }
}
