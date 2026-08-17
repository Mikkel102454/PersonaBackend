package nu.miguel.personabackend.graph;

import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.personabackend.document.YamlDocumentService;
import nu.miguel.personabackend.project.ProjectContentRules;
import nu.miguel.personabackend.reference.ProjectReferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GraphMutationServiceTest {
    private final YamlDocumentService documents = new YamlDocumentService();
    private final GraphProjectionService projections = new GraphProjectionService(documents,
            new ProjectReferenceService(documents), new ProjectContentRules());
    private final GraphMutationService mutations = new GraphMutationService(documents, projections);

    @Test void editsOnlyTheSelectedScalarAndReturnsReparsedProjection() {
        String source = "# header\nid: demo:walk\nscope: player\nroot:\n  id: root\n  type: wait\n  duration: '1s' # exact comment\nfuture: !vendor keep\n";
        GraphMutationResponse result = mutate("behavior", "demo:walk", source,
                op(GraphMutationOperation.Type.EDIT_FIELD, "/root/duration", null, null,
                        null, null, null, null, "2s", null));
        assertEquals(source.replace("'1s'", "\"2s\""), result.content());
        assertEquals("# header", result.content().lines().findFirst().orElseThrow());
        assertTrue(result.content().contains("future: !vendor keep"));
        assertTrue(result.document().valid());
        assertEquals(sha(result.content()), result.contentDigest());
        assertEquals(result.contentDigest(), result.projection().contentDigest());
        assertEquals(List.of("/root/duration"), result.affectedPaths());
    }

    @Test void rejectsStaleDigestAndLeavesInputUntouched() {
        String source = behavior();
        GraphMutationRequest request = request("behavior", "demo:walk", source, List.of(
                op(GraphMutationOperation.Type.DELETE, "/root/children/0", null, null,
                        null, null, null, null, null, null)));
        GraphMutationRequest staleRequest = new GraphMutationRequest(request.graphVersion(), request.path(), request.resourceKind(),
                request.resourceId(), request.yamlPath(), request.content(), "0".repeat(64),
                request.projectFiles(), request.operations());
        GraphContractException error = assertThrows(GraphContractException.class, () -> mutations.mutate(staleRequest));
        assertEquals("STALE_CONTENT", error.code());
        assertEquals(409, error.getStatusCode().value());
        assertEquals(2, projections.project(projectionRequest("behavior", "demo:walk", source)).edges().size());
    }

    @Test void insertsDuplicatesReordersWrapsAndUnwrapsWithNarrowPatches() {
        String source = behavior();
        GraphMutationResponse inserted = mutate("behavior", "demo:walk", source,
                op(GraphMutationOperation.Type.INSERT, null, null, "/root/children",
                        null, null, "wait", "third", null, 1));
        assertTrue(inserted.content().contains("    - id: third\n      type: wait\n      duration: 1s"));
        assertTrue(inserted.content().startsWith("# retained project comment\n"));
        assertTrue(inserted.content().endsWith("extension: !vendor exact\n"));

        GraphMutationResponse duplicated = mutate("behavior", "demo:walk", inserted.content(),
                op(GraphMutationOperation.Type.DUPLICATE, "/root/children/1", null, null,
                        null, null, null, null, null, null));
        assertTrue(duplicated.content().contains("id: duplicate-"));

        GraphMutationResponse moved = mutate("behavior", "demo:walk", inserted.content(),
                op(GraphMutationOperation.Type.REORDER, "/root/children/2", null, "/root/children",
                        null, null, null, null, null, 0));
        assertTrue(moved.content().indexOf("id: second") < moved.content().indexOf("id: first"));

        GraphMutationResponse wrapped = mutate("behavior", "demo:walk", source,
                op(GraphMutationOperation.Type.WRAP, "/root/children/0", null, null,
                        null, null, "sequence", "wrapper", null, null));
        assertTrue(wrapped.content().contains("- id: wrapper\n      type: sequence\n      children:\n        - id: first"));
        var wrapper = find(documents.parse(wrapped.content()).root(), "/root/children/0");
        assertNotNull(wrapper, wrapped.content());
        assertEquals(List.of("id", "type", "children"), wrapper.children().stream().map(value -> value.key()).toList(), wrapped.content());
        assertEquals(1, find(wrapper, "/root/children/0/children").children().size(), wrapped.content());
        GraphMutationResponse unwrapped = mutate("behavior", "demo:walk", wrapped.content(),
                op(GraphMutationOperation.Type.UNWRAP, "/root/children/0", null, null,
                        null, null, null, null, null, null));
        assertEquals(source, unwrapped.content());
    }

    @Test void reconnectsAnOrderedBehaviourBranchAndRejectsCyclesAndLeafParents() {
        String source = "id: demo:walk\nscope: player\nroot:\n  id: root\n  type: sequence\n  children:\n    - id: branch\n      type: sequence\n      children:\n        - id: leaf\n          type: wait\n          duration: 1s\n    - id: spare\n      type: wait\n      duration: 2s\n";
        EditorGraphProjection graph = projections.project(projectionRequest("behavior", "demo:walk", source));
        var branch = graph.nodes().stream().filter(node -> node.title().equals("branch")).findFirst().orElseThrow();
        var spare = graph.nodes().stream().filter(node -> node.title().equals("spare")).findFirst().orElseThrow();
        String output = branch.pins().stream().filter(pin -> pin.direction().equals("output")).findFirst().orElseThrow().id();
        String input = spare.pins().stream().filter(pin -> pin.direction().equals("input")).findFirst().orElseThrow().id();
        GraphMutationResponse connected = mutate("behavior", "demo:walk", source,
                op(GraphMutationOperation.Type.CONNECT, null, null, null, output, input,
                        null, null, null, 1));
        assertTrue(connected.content().contains("      children:\n        - id: leaf"));
        assertTrue(connected.content().contains("        - id: spare\n          type: wait"));

        var leaf = graph.nodes().stream().filter(node -> node.title().equals("leaf")).findFirst().orElseThrow();
        String leafOutput = leaf.pins().stream().filter(pin -> pin.direction().equals("output")).findFirst().orElseThrow().id();
        GraphContractException invalid = assertThrows(GraphContractException.class, () -> mutate("behavior", "demo:walk", source,
                op(GraphMutationOperation.Type.CONNECT, null, null, null, leafOutput, input,
                        null, null, null, null)));
        assertEquals("INVALID_PARENT_NODE", invalid.code());
    }

    @Test void connectsAndDisconnectsExplicitDialogueTransfers() {
        String source = "id: demo:talk\nstart: first\nnodes:\n  first:\n    script:\n      - type: say\n        text: Hello\n  second:\n    script:\n      - type: end-dialogue\n";
        EditorGraphProjection graph = projections.project(projectionRequest("dialogue", "demo:talk", source));
        var first = graph.nodes().stream().filter(node -> node.title().equals("first")).findFirst().orElseThrow();
        var second = graph.nodes().stream().filter(node -> node.title().equals("second")).findFirst().orElseThrow();
        String output = first.pins().stream().filter(pin -> pin.direction().equals("output")).findFirst().orElseThrow().id();
        String input = second.pins().stream().filter(pin -> pin.direction().equals("input")).findFirst().orElseThrow().id();
        GraphMutationResponse connected = mutate("dialogue", "demo:talk", source,
                op(GraphMutationOperation.Type.CONNECT, null, null, null, output, input,
                        null, null, null, null));
        assertTrue(connected.content().contains("      - type: goto\n        node: second"));
        EditorGraphProjection.GraphEdge edge = connected.projection().edges().stream()
                .filter(value -> value.label().equals("transfer")).findFirst().orElseThrow();
        assertEquals("/nodes/first/script/1", edge.sourceYamlPath());
        GraphMutationResponse disconnected = mutate("dialogue", "demo:talk", connected.content(),
                op(GraphMutationOperation.Type.DISCONNECT, edge.sourceYamlPath(), null, null,
                        edge.sourcePinId(), edge.targetPinId(), null, null, null, null));
        assertEquals(source, disconnected.content());
    }

    @Test void boundsOperationsAndRejectsUnsupportedNodeKinds() {
        String source = behavior();
        List<GraphMutationOperation> tooMany = new ArrayList<>();
        for (int index = 0; index <= GraphMutationService.MAX_OPERATIONS; index++)
            tooMany.add(op(GraphMutationOperation.Type.DELETE, "/root/children/0", null, null,
                    null, null, null, null, null, null));
        GraphContractException bounded = assertThrows(GraphContractException.class,
                () -> mutations.mutate(request("behavior", "demo:walk", source, tooMany)));
        assertEquals("INVALID_MUTATION_REQUEST", bounded.code());

        GraphContractException unsupported = assertThrows(GraphContractException.class, () -> mutate("behavior", "demo:walk", source,
                op(GraphMutationOperation.Type.INSERT, null, null, "/root/children", null, null,
                        "arbitrary-browser-yaml", "bad", null, null)));
        assertEquals("UNSUPPORTED_NODE_KIND", unsupported.code());
    }

    @Test void insertsBoundedSchemaDrivenExtensionNodesWithoutAcceptingYamlTemplates() {
        GraphMutationResponse action = mutate("behavior", "demo:walk", behavior(),
                op(GraphMutationOperation.Type.INSERT, null, null, "/root/children", null, null,
                        "extension-action", "extension-node", "vendor:wave", 0));
        assertTrue(action.content().contains("type: action\n      action: vendor:wave"));
        assertTrue(action.projection().nodes().stream().anyMatch(node -> node.title().equals("extension-node")
                && node.badges().contains("extension") && node.extensionOwner().equals("vendor")));

        GraphContractException injected = assertThrows(GraphContractException.class, () -> mutate(
                "behavior", "demo:walk", behavior(), op(GraphMutationOperation.Type.INSERT, null, null,
                        "/root/children", null, null, "extension-action", "safe",
                        "vendor:wave\nowned: true", 0)));
        assertEquals("UNSUPPORTED_NODE_KIND", injected.code());
    }

    @Test void rejectsCyclesCardinalityViolationsAndCustomYamlTargetsWithPreciseCodes() {
        String behavior = "id: demo:walk\nscope: player\nroot:\n  id: root\n  type: sequence\n  children:\n    - id: branch\n      type: sequence\n      children:\n        - id: leaf\n          type: wait\n          duration: 1s\ncustom: !vendor keep\n";
        EditorGraphProjection graph = projections.project(projectionRequest("behavior", "demo:walk", behavior));
        var root = graph.nodes().stream().filter(node -> node.title().equals("root")).findFirst().orElseThrow();
        var branch = graph.nodes().stream().filter(node -> node.title().equals("branch")).findFirst().orElseThrow();
        GraphContractException cycle = assertThrows(GraphContractException.class, () -> mutate("behavior", "demo:walk", behavior,
                op(GraphMutationOperation.Type.CONNECT, null, null, null,
                        branch.pins().stream().filter(pin -> pin.direction().equals("output")).findFirst().orElseThrow().id(),
                        root.pins().stream().filter(pin -> pin.direction().equals("input")).findFirst().orElseThrow().id(),
                        null, null, null, null)));
        assertEquals("CYCLE_NOT_ALLOWED", cycle.code());

        GraphContractException custom = assertThrows(GraphContractException.class, () -> mutate("behavior", "demo:walk", behavior,
                op(GraphMutationOperation.Type.DELETE, "/custom", null, null, null, null, null, null, null, null)));
        assertEquals("NODE_NOT_EDITABLE", custom.code());
        assertEquals("/custom", custom.yamlPath());

        String scripts = "scripts:\n  flow:\n    - type: wait\n      duration: 1s\n    - type: stop\n";
        EditorGraphProjection scriptGraph = projections.project(projectionRequest("script", "flow", scripts));
        var scriptRoot = scriptGraph.nodes().stream().filter(node -> node.kind().equals("reusable-script")).findFirst().orElseThrow();
        var second = scriptGraph.nodes().stream().filter(node -> node.yamlPath().endsWith("/1")).findFirst().orElseThrow();
        GraphContractException cardinality = assertThrows(GraphContractException.class, () -> mutate("script", "flow", scripts,
                op(GraphMutationOperation.Type.CONNECT, null, null, null,
                        scriptRoot.pins().stream().filter(pin -> pin.direction().equals("output")).findFirst().orElseThrow().id(),
                        second.pins().stream().filter(pin -> pin.direction().equals("input")).findFirst().orElseThrow().id(),
                        null, null, null, null)));
        assertEquals("CARDINALITY_EXCEEDED", cardinality.code());
    }

    @Test void rejectsInjectedWrapperKindsAndDestinationsBeforePatchingYaml() {
        String source = behavior();
        GraphContractException wrapper = assertThrows(GraphContractException.class, () -> mutate("behavior", "demo:walk", source,
                op(GraphMutationOperation.Type.WRAP, "/root/children/0", null, null, null, null,
                        "sequence\nowned: true", "safe", null, null)));
        assertEquals("UNSUPPORTED_NODE_KIND", wrapper.code());
        GraphContractException destination = assertThrows(GraphContractException.class, () -> mutate("behavior", "demo:walk", source,
                op(GraphMutationOperation.Type.INSERT, null, null, "/extension", null, null,
                        "wait", "injected", null, null)));
        assertEquals("INVALID_INSERT_DESTINATION", destination.code());
        assertFalse(source.contains("injected"));
    }

    @Test void copiesAnExactBehaviorNodeAcrossCompatibleDraftGraphsAndOnlyChangesItsStableId() {
        String from = "id: demo:source\nscope: player\nroot:\n  id: root\n  type: sequence\n  children:\n"
                + "    - id: original # copied comment\n      type: wait\n      duration: '7s' # style retained\n      vendor: !custom exact\n";
        String target = "id: demo:target\nscope: player\nroot:\n  id: root\n  type: sequence\n  children: []\nfuture: retained\n";
        List<ContentFile> files = List.of(file("behaviors/source.yml", from), file("behaviors/target.yml", target));
        GraphMutationOperation copy = new GraphMutationOperation(GraphMutationOperation.Type.COPY,
                "/root/children/0", null, "/root/children", null, null, null,
                "copy-one", null, 0, "behaviors/source.yml");
        GraphMutationResponse result = mutations.mutate(new GraphMutationRequest(EditorGraphProjection.VERSION,
                "behaviors/target.yml", "behavior", "demo:target", "", target, sha(target), files, List.of(copy)));
        assertTrue(result.content().contains("# copied comment"));
        assertTrue(result.content().contains("id: \"copy-one\" # copied comment"));
        assertTrue(result.content().contains("duration: '7s' # style retained"));
        assertTrue(result.content().contains("vendor: !custom exact"));
        assertTrue(result.content().endsWith("future: retained\n"));
    }

    @Test void fuzzesEscapedYamlPathsAndScalarValuesWithoutChangingNeighborBytes() {
        String original = "id: demo:talk\nstart: a/b\nnodes:\n  a/b:\n    script:\n      - type: say\n        text: 'original' # preserve\n        extension: !vendor tagged\n  untouched:\n    script: [{type: end-dialogue}]\n";
        Random random = new Random(42);
        for (int iteration = 0; iteration < 32; iteration++) {
            String value = "value-" + iteration + "-" + Integer.toUnsignedString(random.nextInt(), 36);
            GraphMutationResponse result = mutate("dialogue", "demo:talk", original,
                    op(GraphMutationOperation.Type.EDIT_FIELD, "/nodes/a~1b/script/0/text", null, null,
                            null, null, null, null, value, null));
            assertTrue(result.content().contains("text: \"" + value + "\" # preserve"));
            assertTrue(result.content().contains("extension: !vendor tagged"));
            assertTrue(result.content().endsWith("  untouched:\n    script: [{type: end-dialogue}]\n"));
            assertTrue(result.document().valid());
        }
    }

    @Test void goldenPaletteTemplatesRoundTripEveryBuiltInFamilyWithoutTouchingSentinels() {
        String behavior = "# golden\nid: demo:tree\nscope: player\nroot:\n  id: root\n  type: sequence\n  children: []\nsentinel: !vendor keep\n";
        for (String kind : List.of("sequence", "selector", "priority-selector", "parallel", "action",
                "condition", "checkpoint", "wait", "cooldown", "extension-action", "extension-condition")) {
            GraphMutationResponse result = mutate("behavior", "demo:tree", behavior,
                    op(GraphMutationOperation.Type.INSERT, null, null, "/root/children", null, null,
                            kind, "golden-" + kind, kind.startsWith("extension-") ? "vendor:golden" : null, null));
            assertGolden(result, kind);
        }

        String dialogue = "# golden\nid: demo:talk\nstart: start\nnodes:\n  start:\n    script: []\nsentinel: !vendor keep\n";
        for (String kind : List.of("say", "wait", "if", "choice", "random", "run-script", "goto",
                "end-dialogue", "extension-command")) {
            GraphMutationResponse result = mutate("dialogue", "demo:talk", dialogue,
                    op(GraphMutationOperation.Type.INSERT, null, null, "/nodes/start/script", null, null,
                            kind, null, kind.equals("extension-command") ? "vendor:golden" : null, null));
            assertGolden(result, kind);
        }
        assertGolden(mutate("dialogue", "demo:talk", "# golden\nid: demo:talk\nstart: start\nnodes: {}\nsentinel: !vendor keep\n",
                op(GraphMutationOperation.Type.INSERT, null, null, "/nodes", null, null,
                        "dialogue-entry", "start", null, null)), "dialogue-entry");

        String quest = "# golden\nid: demo:quest\ntitle: Quest\nphases:\n  - id: start\n    objectives: []\nsentinel: !vendor keep\n";
        assertGolden(mutate("quest", "demo:quest", quest,
                op(GraphMutationOperation.Type.INSERT, null, null, "/phases", null, null,
                        "quest-phase", "second", null, null)), "quest-phase");
        for (String kind : List.of("quest-objective", "extension-objective"))
            assertGolden(mutate("quest", "demo:quest", quest,
                    op(GraphMutationOperation.Type.INSERT, null, null, "/phases/0/objectives", null, null,
                            kind, "objective-" + kind, kind.startsWith("extension-") ? "vendor:golden" : null, null)), kind);
        assertGolden(mutate("quest", "demo:quest", quest,
                op(GraphMutationOperation.Type.INSERT, null, null, "/phases/0/on-start", null, null,
                        "script-say", null, null, null)), "quest lifecycle");

        String npc = "# golden\nid: demo:npc\ndisplay-name: NPC\nsentinel: !vendor keep\n";
        assertGolden(mutate("npc", "demo:npc", npc,
                op(GraphMutationOperation.Type.INSERT, null, null, "/anchors", null, null,
                        "npc-anchor", "home", null, null)), "npc-anchor");
        assertGolden(mutate("npc", "demo:npc", npc,
                op(GraphMutationOperation.Type.INSERT, null, null, "/on-interact", null, null,
                        "extension-command", null, "vendor:golden", null)), "npc lifecycle");

        String scripts = "# golden\nscripts:\n  flow: []\nsentinel: !vendor keep\n";
        for (String kind : List.of("say", "wait", "if", "choice", "random", "run-script", "goto", "stop",
                "extension-command"))
            assertGolden(mutate("script", "flow", scripts,
                    op(GraphMutationOperation.Type.INSERT, null, null, "/scripts/flow", null, null,
                            kind, null, kind.equals("extension-command") ? "vendor:golden" : null, null)), kind);
    }

    private static void assertGolden(GraphMutationResponse result, String label) {
        assertTrue(result.document().valid(), label);
        assertTrue(result.content().startsWith("# golden\n"), label);
        assertEquals(1, result.content().split("sentinel: !vendor keep", -1).length - 1, label + "\n" + result.content());
    }

    private GraphMutationResponse mutate(String kind, String id, String source, GraphMutationOperation... operations) {
        return mutations.mutate(request(kind, id, source, List.of(operations)));
    }
    private static GraphMutationRequest request(String kind, String id, String source,
                                                List<GraphMutationOperation> operations) {
        return new GraphMutationRequest(EditorGraphProjection.VERSION, path(kind), kind, id,
                kind.equals("script") ? "/scripts/" + id : "", source, sha(source), List.of(), operations);
    }
    private static GraphProjectionRequest projectionRequest(String kind, String id, String source) {
        return new GraphProjectionRequest(path(kind), kind, id, kind.equals("script") ? "/scripts/" + id : "",
                source, sha(source), List.of());
    }
    private static String path(String kind) { return kind.equals("script") ? "scripts.yml" : kind + "s/test.yml"; }
    private static GraphMutationOperation op(GraphMutationOperation.Type type, String yamlPath,
                                             String targetYamlPath, String parentYamlPath,
                                             String sourcePin, String targetPin, String nodeKind,
                                             String key, String value, Integer index) {
        return new GraphMutationOperation(type, yamlPath, targetYamlPath, parentYamlPath,
                sourcePin, targetPin, nodeKind, key, value, index, null);
    }
    private static String behavior() {
        return "# retained project comment\nid: demo:walk\nscope: player\nroot:\n  id: root\n  type: sequence\n  children:\n    - id: first\n      type: wait\n      duration: '1s'\n    - id: second\n      type: action\n      action: set-visible\n      visible: true\nextension: !vendor exact\n";
    }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ContentFile file(String path, String content) { return new ContentFile(path, sha(content), content); }
    private static nu.miguel.personabackend.document.YamlDocumentNode find(
            nu.miguel.personabackend.document.YamlDocumentNode node, String path) {
        if (node == null) return null; if (node.path().equals(path)) return node;
        for (var child : node.children()) { var found = find(child, path); if (found != null) return found; }
        return null;
    }
}
