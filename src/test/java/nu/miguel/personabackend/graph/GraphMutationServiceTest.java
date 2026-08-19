package nu.miguel.personabackend.graph;

import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.persona.editor.protocol.EditorSchemaDocument;
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
        assertEquals("STALE_PROJECTION", error.code());
        assertEquals(sha(source), error.currentContentDigest());
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
                reorder(inserted.content(), "/root/children/2", "/root/children/0", true));
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
        String output = branch.pins().stream().filter(pin -> pin.direction().equals("OUTPUT")).findFirst().orElseThrow().id();
        String input = spare.pins().stream().filter(pin -> pin.direction().equals("INPUT")).findFirst().orElseThrow().id();
        GraphMutationResponse connected = mutate("behavior", "demo:walk", source,
                op(GraphMutationOperation.Type.CONNECT, null, null, null, output, input,
                        null, null, null, 1));
        assertTrue(connected.content().contains("      children:\n        - id: leaf"));
        assertTrue(connected.content().contains("        - id: spare\n          type: wait"));

        var leaf = graph.nodes().stream().filter(node -> node.title().equals("leaf")).findFirst().orElseThrow();
        assertTrue(leaf.pins().stream().noneMatch(pin -> pin.direction().equals("OUTPUT")),
                "Leaf outcomes are status only unless the schema declares a branch");
        GraphContractException invalid = assertThrows(GraphContractException.class, () -> mutate("behavior", "demo:walk", source,
                op(GraphMutationOperation.Type.CONNECT, null, null, null, leaf.id() + ":out:forged", input,
                        null, null, null, null)));
        assertEquals("PIN_NOT_FOUND", invalid.code());
    }

    @Test void connectsAndDisconnectsExplicitDialogueGraphWires() {
        String source = "content-version: 2\nid: demo:talk\nstart: first\nnodes:\n  first:\n    graph:\n      variables: {}\n      nodes:\n        line: { type: say, text: Hello }\n        end: { type: end-dialogue }\n      connections:\n        enter: { from: $event.exec, to: line.exec }\n";
        EditorGraphProjection graph = projections.project(projectionRequest("dialogue", "demo:talk", source));
        var first = graph.nodes().stream().filter(node -> node.title().equals("line")).findFirst().orElseThrow();
        var second = graph.nodes().stream().filter(node -> node.title().equals("end")).findFirst().orElseThrow();
        String output = first.pins().stream().filter(pin -> pin.direction().equals("OUTPUT")&&pin.label().equals("success")).findFirst().orElseThrow().id();
        String input = second.pins().stream().filter(pin -> pin.direction().equals("INPUT")&&pin.label().equals("exec")).findFirst().orElseThrow().id();
        GraphMutationResponse connected = mutate("dialogue", "demo:talk", source,
                op(GraphMutationOperation.Type.CONNECT, null, null, null, output, input,
                        null, "finish", null, null));
        assertTrue(connected.content().contains("finish:")&&connected.content().contains("from: line.success"));
        EditorGraphProjection.GraphEdge edge = connected.projection().edges().stream()
                .filter(value -> value.label().equals("finish")).findFirst().orElseThrow();
        assertEquals("/nodes/first/graph/connections/finish/from", edge.sourceYamlPath());
        GraphMutationResponse disconnected = mutate("dialogue", "demo:talk", connected.content(),
                op(GraphMutationOperation.Type.DISCONNECT, edge.sourceYamlPath(), null, null,
                        edge.sourcePinId(), edge.targetPinId(), null, null, null, null));
        assertEquals(source, disconnected.content());
    }

    @Test void insertsAndConnectsAnExplicitNpcCommandAsOneGesture() {
        String source = "content-version: 2\nid: village:vander\ndisplay-name: Vander\non-click:\n"
                + "  variables: {}\n  nodes:\n    pause: { type: wait, duration: 1s }\n"
                + "  connections:\n    enter: { from: $event.exec, to: pause.exec }\n";
        EditorGraphProjection graph = projections.project(projectionRequest("npc", "village:vander", source));
        String sourcePin = graph.nodes().stream().filter(node -> node.title().equals("pause")).findFirst().orElseThrow()
                .pins().stream().filter(pin -> pin.direction().equals("OUTPUT") && pin.label().equals("success"))
                .findFirst().orElseThrow().id();
        String targetPin = "npc:village:vander#graph:" + sha("/on-click").substring(0, 10)
                + ":node:give-item:input:exec";
        GraphMutationOperation insert = op(GraphMutationOperation.Type.INSERT, null, null, "/on-click/nodes",
                null, null, "give-item", "give-item", null, null);
        GraphMutationOperation connect = op(GraphMutationOperation.Type.CONNECT, null, null, null,
                sourcePin, targetPin, null, "wire-test", null, null);
        GraphMutationOperation compound = new GraphMutationOperation(GraphMutationOperation.Type.COMPOUND,
                null, null, null, null, null, null, null, null, null, null,
                UUID.randomUUID().toString(), null, null, null, null, null, null, List.of(insert, connect));

        GraphMutationResponse result = mutate("npc", "village:vander", source, compound);

        assertTrue(result.content().contains("give-item:\n      type: give-item"), result.content());
        assertTrue(result.content().contains("from: pause.success"), result.content());
        assertTrue(result.content().contains("to: give-item.exec"), result.content());
    }

    @Test void deletedExplicitNpcCommandCanBeReinsertedAndConnectedWithTheSameKey() {
        String source = "content-version: 2\nid: village:vander\ndisplay-name: Vander\non-click:\n"
                + "  variables: {}\n  nodes:\n    pause: { type: wait, duration: 1s }\n"
                + "    give-item: { type: give-item, material: DIAMOND }\n"
                + "  connections:\n    enter: { from: $event.exec, to: pause.exec }\n"
                + "    reward: { from: pause.success, to: give-item.exec }\n";
        GraphMutationResponse deleted = mutate("npc", "village:vander", source,
                op(GraphMutationOperation.Type.DELETE, "/on-click/nodes/give-item", null, null,
                        null, null, null, null, null, null));
        assertNull(find(documents.parse(deleted.content()).root(), "/on-click/nodes/give-item"), deleted.content());
        assertNull(find(documents.parse(deleted.content()).root(), "/on-click/connections/reward"), deleted.content());

        EditorGraphProjection graph = deleted.projection();
        String sourcePin = graph.nodes().stream().filter(node -> node.title().equals("pause")).findFirst().orElseThrow()
                .pins().stream().filter(pin -> pin.direction().equals("OUTPUT") && pin.label().equals("success"))
                .findFirst().orElseThrow().id();
        String targetPin = "npc:village:vander#graph:" + sha("/on-click").substring(0, 10)
                + ":node:give-item:input:exec";
        GraphMutationOperation insert = op(GraphMutationOperation.Type.INSERT, null, null, "/on-click/nodes",
                null, null, "give-item", "give-item", null, null);
        GraphMutationOperation connect = op(GraphMutationOperation.Type.CONNECT, null, null, null,
                sourcePin, targetPin, null, "wire-reward", null, null);
        GraphMutationOperation compound = new GraphMutationOperation(GraphMutationOperation.Type.COMPOUND,
                null, null, null, null, null, null, null, null, null, null,
                UUID.randomUUID().toString(), null, null, null, null, null, null, List.of(insert, connect));

        GraphMutationResponse reinserted = mutate("npc", "village:vander", deleted.content(), compound);
        assertNotNull(find(documents.parse(reinserted.content()).root(), "/on-click/nodes/give-item"), reinserted.content());
        assertEquals("give-item.exec", find(documents.parse(reinserted.content()).root(),
                "/on-click/connections/wire-reward/to").value());
    }

    @Test void insertsAndConnectsNpcCommandAfterTheLastNodeWasDeleted() {
        String source = "content-version: 2\nid: village:re\ndisplay-name: \"New NPC\"\non-click:\n"
                + "  variables: {}\n  nodes:\n  connections:\n";
        EditorGraphProjection graph = projections.project(projectionRequest("npc", "village:re", source));
        String sourcePin = graph.nodes().stream().filter(node -> node.kind().equals("event")
                        && node.title().equals("On Click"))
                .flatMap(node -> node.pins().stream())
                .filter(pin -> pin.direction().equals("OUTPUT") && pin.label().equals("exec"))
                .findFirst().orElseThrow().id();
        String targetPin = "npc:village:re#graph:" + sha("/on-click").substring(0, 10)
                + ":node:give-item:input:exec";
        GraphMutationOperation insert = op(GraphMutationOperation.Type.INSERT, null, null, "/on-click/nodes",
                null, null, "give-item", "give-item", null, null);
        GraphMutationOperation connect = op(GraphMutationOperation.Type.CONNECT, null, null, null,
                sourcePin, targetPin, null, "enter", null, null);
        GraphMutationOperation compound = new GraphMutationOperation(GraphMutationOperation.Type.COMPOUND,
                null, null, null, null, null, null, null, null, null, null,
                UUID.randomUUID().toString(), null, null, null, null, null, null, List.of(insert, connect));

        GraphMutationResponse result = mutate("npc", "village:re", source, compound);

        assertNotNull(find(documents.parse(result.content()).root(), "/on-click/nodes/give-item"), result.content());
        assertEquals("$event.exec", find(documents.parse(result.content()).root(),
                "/on-click/connections/enter/from").value());
        assertEquals("give-item.exec", find(documents.parse(result.content()).root(),
                "/on-click/connections/enter/to").value());
    }

    @Test void setsExistingAndSynthesizedInlineScriptPinDefaults() {
        String source = "content-version: 2\nid: flow\ninputs: {}\noutputs: {}\nvariables: {}\nnodes:\n"
                + "  pause: { type: wait, duration: 1s }\n"
                + "  reward: { type: give-item }\nconnections: {}\n";
        EditorGraphProjection graph = projections.project(projectionRequest("script", "flow", source));
        var pause = graph.nodes().stream().filter(node -> node.title().equals("pause")).findFirst().orElseThrow();
        var reward = graph.nodes().stream().filter(node -> node.title().equals("reward")).findFirst().orElseThrow();
        String duration = pause.pins().stream().filter(pin -> pin.label().equals("duration")).findFirst().orElseThrow().id();
        String material = reward.pins().stream().filter(pin -> pin.label().equals("material")).findFirst().orElseThrow().id();

        GraphMutationResponse edited = mutate("script", "flow", source, pinDefault(duration, "2s"));
        assertEquals("2s", find(documents.parse(edited.content()).root(), "/nodes/pause/duration").value());
        GraphMutationResponse inserted = mutate("script", "flow", edited.content(), pinDefault(material, "DIAMOND"));
        assertEquals("DIAMOND", find(documents.parse(inserted.content()).root(), "/nodes/reward/material").value());
        assertTrue(inserted.content().contains("reward: { type: give-item, material: \"DIAMOND\" }"));
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
        assertEquals("UNSIGNED_EXTENSION_SCHEMA", injected.code());
    }

    @Test void rejectsCyclesCardinalityViolationsAndCustomYamlTargetsWithPreciseCodes() {
        String behavior = "id: demo:walk\nscope: player\nroot:\n  id: root\n  type: sequence\n  children:\n    - id: branch\n      type: sequence\n      children:\n        - id: leaf\n          type: wait\n          duration: 1s\ncustom: !vendor keep\n";
        EditorGraphProjection graph = projections.project(projectionRequest("behavior", "demo:walk", behavior));
        var root = graph.nodes().stream().filter(node -> node.title().equals("root")).findFirst().orElseThrow();
        var branch = graph.nodes().stream().filter(node -> node.title().equals("branch")).findFirst().orElseThrow();
        GraphContractException cycle = assertThrows(GraphContractException.class, () -> mutate("behavior", "demo:walk", behavior,
                op(GraphMutationOperation.Type.CONNECT, null, null, null,
                        branch.pins().stream().filter(pin -> pin.direction().equals("OUTPUT")).findFirst().orElseThrow().id(),
                        branch.pins().stream().filter(pin -> pin.direction().equals("INPUT")).findFirst().orElseThrow().id(),
                        null, null, null, null)));
        assertEquals("CYCLE_NOT_ALLOWED", cycle.code());

        GraphContractException custom = assertThrows(GraphContractException.class, () -> mutate("behavior", "demo:walk", behavior,
                op(GraphMutationOperation.Type.DELETE, "/custom", null, null, null, null, null, null, null, null)));
        assertEquals("NODE_NOT_EDITABLE", custom.code());
        assertEquals("/custom", custom.yamlPath());

        String scripts = "content-version: 2\nid: flow\ninputs: {}\noutputs: {}\nvariables: {}\nnodes:\n  wait: { type: wait, duration: 1s }\nconnections:\n  enter: { from: $input.exec, to: wait.exec }\n  leave: { from: wait.success, to: $output.exec }\n";
        EditorGraphProjection scriptGraph = projections.project(projectionRequest("script", "flow", scripts));
        var scriptRoot = scriptGraph.nodes().stream().filter(node -> node.kind().equals("script-input")).findFirst().orElseThrow();
        var second = scriptGraph.nodes().stream().filter(node -> node.title().equals("wait")).findFirst().orElseThrow();
        GraphMutationResponse replaced = mutate("script", "flow", scripts,
                op(GraphMutationOperation.Type.CONNECT, null, null, null,
                        scriptRoot.pins().stream().filter(pin -> pin.direction().equals("OUTPUT")).findFirst().orElseThrow().id(),
                        second.pins().stream().filter(pin -> pin.direction().equals("INPUT")).findFirst().orElseThrow().id(),
                        null, "replacement", null, null));
        assertFalse(replaced.content().contains("enter:"));
        assertTrue(replaced.content().contains("replacement:"));
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

    @Test void copiesAndRenamesKeyedGraphNodesWhilePreservingInternalEndpointsAndSourceBytes() {
        String source = "content-version: 2\nid: flow\ninputs: {}\noutputs: {}\nvariables: {}\nnodes:\n"
                + "  first: # exact node comment\n    type: wait\n    duration: '2s' # scalar style\n"
                + "  last: { type: stop }\nconnections:\n"
                + "  enter: { from: $input.exec, to: first.exec }\n"
                + "  finish: { from: first.success, to: last.exec }\n";
        GraphMutationResponse copied = mutate("script", "flow", source,
                new GraphMutationOperation(GraphMutationOperation.Type.COPY, "/nodes/first", null, "/nodes",
                        null, null, null, "first-copy", null, null, "scripts/test.yml"));
        assertTrue(copied.content().contains("first-copy: # exact node comment"));
        assertTrue(copied.content().contains("duration: '2s' # scalar style"));

        GraphMutationOperation rename = new GraphMutationOperation(GraphMutationOperation.Type.RENAME_NODE,
                "/nodes/first", null, null, null, null, null, null, null, null, null,
                "rename-node", null, null, null, null, null, null, List.of(), null, null, null, null, "opening");
        GraphMutationResponse renamed = mutate("script", "flow", copied.content(), rename);
        assertNotNull(find(documents.parse(renamed.content()).root(), "/nodes/opening"));
        assertNull(find(documents.parse(renamed.content()).root(), "/nodes/first"));
        assertEquals("opening.exec", find(documents.parse(renamed.content()).root(), "/connections/enter/to").value());
        assertEquals("opening.success", find(documents.parse(renamed.content()).root(), "/connections/finish/from").value());
        assertTrue(renamed.content().contains("'2s' # scalar style"));
    }

    @Test void fuzzesEscapedYamlPathsAndScalarValuesWithoutChangingNeighborBytes() {
        String original = "content-version: 2\nid: demo:talk\nstart: a/b\nnodes:\n  a/b:\n    graph:\n      variables: {}\n      nodes:\n        say:\n          type: say\n          text: 'original' # preserve\n          extension: !vendor tagged\n      connections: {}\n  untouched:\n    graph:\n      variables: {}\n      nodes: { end: { type: end-dialogue } }\n      connections: {}\n";
        Random random = new Random(42);
        for (int iteration = 0; iteration < 32; iteration++) {
            String value = "value-" + iteration + "-" + Integer.toUnsignedString(random.nextInt(), 36);
            GraphMutationResponse result = mutate("dialogue", "demo:talk", original,
                    op(GraphMutationOperation.Type.EDIT_FIELD, "/nodes/a~1b/graph/nodes/say/text", null, null,
                            null, null, null, null, value, null));
            assertTrue(result.content().contains("text: \"" + value + "\" # preserve"));
            assertTrue(result.content().contains("extension: !vendor tagged"));
            assertTrue(result.content().endsWith("      nodes: { end: { type: end-dialogue } }\n      connections: {}\n"));
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

        String dialogue = "# golden\ncontent-version: 2\nid: demo:talk\nstart: start\nnodes:\n  start:\n    graph:\n      variables: {}\n      nodes: {}\n      connections: {}\nsentinel: !vendor keep\n";
        for (String kind : List.of("say", "wait", "branch", "choice", "sequence", "switch", "random", "gate",
                "do-once", "do-n", "for", "for-each", "while", "run-script", "goto", "end-dialogue", "extension-command")) {
            GraphMutationResponse result = mutate("dialogue", "demo:talk", dialogue,
                    op(GraphMutationOperation.Type.INSERT, null, null, "/nodes/start/graph/nodes", null, null,
                            kind, "golden-"+kind, kind.equals("extension-command") ? "vendor:golden" : null, null));
            assertGolden(result, kind);
        }
        GraphMutationResponse dialogueEntry = mutate("dialogue", "demo:talk", "# golden\ncontent-version: 2\nid: demo:talk\nstart: start\nnodes: {}\nsentinel: !vendor keep\n",
                op(GraphMutationOperation.Type.INSERT, null, null, "/nodes", null, null,
                        "dialogue-entry", "start", null, null));
        assertGolden(dialogueEntry, "dialogue-entry");
        assertTrue(dialogueEntry.content().contains("graph:\n      variables: {}"));
        assertFalse(dialogueEntry.content().contains("script:"));

        String quest = "# golden\ncontent-version: 2\nid: demo:quest\ntitle: Quest\nphases:\n  - id: start\n    objectives: []\nsentinel: !vendor keep\n";
        assertGolden(mutate("quest", "demo:quest", quest,
                op(GraphMutationOperation.Type.INSERT, null, null, "/phases", null, null,
                        "quest-phase", "second", null, null)), "quest-phase");
        for (String kind : List.of("quest-objective", "extension-objective"))
            assertGolden(mutate("quest", "demo:quest", quest,
                    op(GraphMutationOperation.Type.INSERT, null, null, "/phases/0/objectives", null, null,
                            kind, "objective-" + kind, kind.startsWith("extension-") ? "vendor:golden" : null, null)), kind);
        assertGolden(mutate("quest", "demo:quest", quest,
                op(GraphMutationOperation.Type.INSERT, null, null, "/phases/0/on-start/nodes", null, null,
                        "say", "line", null, null)), "quest lifecycle");

        String npc = "# golden\ncontent-version: 2\nid: demo:npc\ndisplay-name: NPC\nsentinel: !vendor keep\n";
        assertGolden(mutate("npc", "demo:npc", npc,
                op(GraphMutationOperation.Type.INSERT, null, null, "/anchors", null, null,
                        "npc-anchor", "home", null, null)), "npc-anchor");
        assertGolden(mutate("npc", "demo:npc", npc,
                op(GraphMutationOperation.Type.INSERT, null, null, "/on-click/nodes", null, null,
                        "extension-command", "custom", "vendor:golden", null)), "npc lifecycle");

        String scripts = "# golden\ncontent-version: 2\nid: flow\ninputs: {}\noutputs: {}\nvariables: {}\nnodes: {}\nconnections: {}\nsentinel: !vendor keep\n";
        GraphMutationOperation value=new GraphMutationOperation(GraphMutationOperation.Type.CREATE_VALUE_NODE,null,null,null,null,null,null,"answer","true",null,null,null,null,null,null,null,null,null,List.of(),"boolean",null,null,null,null);
        assertGolden(mutate("script","flow",scripts,value),"value");
    }

    @Test void valueAndVariableCreationMaterializePreviouslyEmptyEventGraphs() {
        String npc = "content-version: 2\nid: demo:npc\ndisplay-name: NPC\n";
        GraphMutationOperation value = new GraphMutationOperation(GraphMutationOperation.Type.CREATE_VALUE_NODE,
                null, null, "/on-click/nodes", null, null, null, "dialogue", "demo:talk", null, null,
                null, null, null, null, null, null, null, List.of(), "dialogue", null, null, null, null);
        GraphMutationResponse created = mutate("npc", "demo:npc", npc, value);
        assertEquals("dialogue", find(documents.parse(created.content()).root(), "/on-click/nodes/dialogue/value-type").value());
        assertEquals("demo:talk", find(documents.parse(created.content()).root(), "/on-click/nodes/dialogue/value").value());
        assertNotNull(find(documents.parse(created.content()).root(), "/on-click/variables"));
        assertNotNull(find(documents.parse(created.content()).root(), "/on-click/connections"));

        GraphMutationResponse variable = mutate("npc", "demo:npc", npc,
                advanced(GraphMutationOperation.Type.ADD_VARIABLE, "/on-damage", null, null,
                        "hit-count", "integer", null, null));
        assertEquals("integer", find(documents.parse(variable.content()).root(), "/on-damage/variables/hit-count/type").value());
        assertNotNull(find(documents.parse(variable.content()).root(), "/on-damage/nodes"));
        assertNotNull(find(documents.parse(variable.content()).root(), "/on-damage/connections"));
    }

    @Test void scriptParameterRenameIsAtomicAcrossProjectCallSites() {
        String scripts = "content-version: 2\nid: flow\ninputs:\n  who: { type: text, required: true }\n"
                + "outputs: {}\nvariables: {}\nnodes:\n  say: { type: say, text: placeholder }\n"
                + "connections:\n  enter: { from: $input.exec, to: say.exec }\n"
                + "  data: { from: $input.who, to: say.text }\n"
                + "  leave: { from: say.success, to: $output.exec }\n";
        String caller = "content-version: 2\nid: caller\ninputs: {}\noutputs: {}\nvariables: {}\nnodes:\n"
                + "  call:\n    type: run-script\n    script: flow\n    inputs:\n      who: 'Alex' # keep style\nconnections: {}\n";
        String quest = "content-version: 2\nid: demo:quest\nphases:\n  - id: start\n    objectives: []\n    on-start:\n"
                + "      variables: {}\n      nodes:\n        call:\n          type: run-script\n          script: flow\n          inputs:\n            who: Steve # neighbor\n      connections: {}\n";
        List<ContentFile> files = List.of(file("scripts/flow.yml", scripts), file("scripts/caller.yml",caller), file("quests/demo.yml", quest));
        GraphMutationOperation rename = new GraphMutationOperation(
                GraphMutationOperation.Type.RENAME_SCRIPT_PARAMETER, null, null,
                "/inputs", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, List.of(),
                null, null, null, "who", "recipient");
        GraphMutationResponse result = mutations.mutate(new GraphMutationRequest(EditorGraphProjection.VERSION,
                "scripts/flow.yml", "script", "flow", "", scripts, sha(scripts), files, List.of(rename)));
        String updatedScripts = result.rawFiles().stream().filter(file -> file.path().equals("scripts/flow.yml"))
                .findFirst().orElseThrow().content();
        String updatedCaller = result.rawFiles().stream().filter(file -> file.path().equals("scripts/caller.yml"))
                .findFirst().orElseThrow().content();
        String updatedQuest = result.rawFiles().stream().filter(file -> file.path().equals("quests/demo.yml"))
                .findFirst().orElseThrow().content();
        assertTrue(updatedScripts.contains("recipient: { type: text, required: true }"));
        assertTrue(updatedScripts.contains("from: \"$input.recipient\""));
        assertTrue(updatedCaller.contains("recipient: 'Alex' # keep style"));
        assertTrue(updatedQuest.contains("recipient: Steve # neighbor"));
        assertFalse(updatedScripts.contains("who:"));assertFalse(updatedCaller.contains("who:"));
        assertFalse(updatedQuest.contains("who:"));
        assertEquals(3, result.patches().stream().map(GraphMutationResponse.SourcePatch::filePath).distinct().count());
    }

    @Test void explicitCompoundIsAtomicReturnsMinimalPatchAndBoundsNestedChildren() {
        String source = behavior();
        GraphMutationOperation compound = new GraphMutationOperation(GraphMutationOperation.Type.COMPOUND,
                null, null, null, null, null, null, null, null, null, null,
                "compound-1", null, null, null, null, null, null, List.of(
                op(GraphMutationOperation.Type.EDIT_FIELD, "/root/children/0/duration", null, null,
                        null, null, null, null, "3s", null),
                reorder(source, "/root/children/1", "/root/children/0", true)));
        GraphMutationResponse result = mutations.mutate(request("behavior", "demo:walk", source, List.of(compound)));
        assertEquals(2, result.appliedOperationCount());
        assertEquals(2, result.patches().size());
        String rebuilt = source;
        for (GraphMutationResponse.SourcePatch patch : result.patches())
            rebuilt = rebuilt.substring(0, patch.beforeStartOffset()) + patch.after()
                    + rebuilt.substring(patch.beforeEndOffset());
        assertEquals(result.content(), rebuilt);
        assertTrue(result.content().startsWith("# retained project comment\n"));
        assertTrue(result.content().endsWith("extension: !vendor exact\n"));

        GraphMutationOperation unsafe = new GraphMutationOperation(GraphMutationOperation.Type.COMPOUND,
                null, null, null, null, null, null, null, null, null, null,
                "compound-2", null, null, null, null, null, null, List.of(
                op(GraphMutationOperation.Type.EDIT_FIELD, "/root/children/0/duration", null, null,
                        null, null, null, null, "9s", null),
                op(GraphMutationOperation.Type.DELETE, "/extension", null, null,
                        null, null, null, null, null, null)));
        assertThrows(GraphContractException.class,
                () -> mutations.mutate(request("behavior", "demo:walk", source, List.of(unsafe))));
        assertEquals("1s", find(documents.parse(source).root(), "/root/children/0/duration").value());
    }

    @Test void returnsCreatedStableIdentityForOperationFocusRestoration() {
        String operationId = "insert-focus";
        GraphMutationOperation insert = new GraphMutationOperation(GraphMutationOperation.Type.INSERT,
                null, null, "/root/children", null, null, "wait", "focused", null, 0, null,
                operationId, null, null, null, null, null, null, List.of());
        GraphMutationResponse result = mutate("behavior", "demo:walk", behavior(), insert);
        assertEquals("behavior:demo:walk#focused", result.identityRemap().get(operationId));
        assertTrue(result.projection().nodes().stream().anyMatch(node -> node.id().equals(result.identityRemap().get(operationId))));
    }

    @Test void insertsVisibleAutocastsAndSupportsVariablePromotionRenameTypeAndDeleteRules() {
        String source="content-version: 2\nid: flow\ninputs: {}\noutputs: {}\nvariables: {}\nnodes:\n"
                +"  literal: { type: value, value-type: string, value: hello }\n"
                +"  line: { type: say, text: fallback }\n  pause: { type: wait, duration: 1s }\n"
                +"connections:\n  enter: { from: $input.exec, to: line.exec }\n  next: { from: line.success, to: pause.exec }\n  leave: { from: pause.success, to: $output.exec }\n";
        EditorGraphProjection graph=projections.project(projectionRequest("script","flow",source));
        GraphNodePair pair=new GraphNodePair(graph.nodes().stream().filter(node->node.title().equals("literal")).findFirst().orElseThrow(),
                graph.nodes().stream().filter(node->node.title().equals("line")).findFirst().orElseThrow());
        String output=pair.source().pins().stream().filter(pin->pin.direction().equals("OUTPUT")).findFirst().orElseThrow().id();
        String input=pair.target().pins().stream().filter(pin->pin.direction().equals("INPUT")&&pin.label().equals("text")).findFirst().orElseThrow().id();
        GraphMutationResponse cast=mutate("script","flow",source,advanced(GraphMutationOperation.Type.CONNECT_WITH_AUTOCAST,null,output,input,"text",null,null,null));
        assertTrue(cast.content().contains("text-cast:")&&cast.content().contains("type: string-to-text"));
        assertTrue(cast.projection().edges().stream().filter(edge->edge.label().startsWith("text-")).count()==2);

        EditorGraphProjection castGraph=cast.projection();
        var pause=castGraph.nodes().stream().filter(node->node.title().equals("pause")).findFirst().orElseThrow();
        String duration=pause.pins().stream().filter(pin->pin.direction().equals("INPUT")&&pin.label().equals("duration")).findFirst().orElseThrow().id();
        GraphMutationResponse promoted=mutations.mutate(request("script","flow",cast.content(),List.of(
                advanced(GraphMutationOperation.Type.PROMOTE_TO_VARIABLE,null,null,duration,"delay",null,null,null))));
        assertTrue(promoted.content().contains("delay:\n    type: duration"));
        assertTrue(promoted.content().contains("default: 1s"));
        assertTrue(promoted.content().contains("type: get-variable\n    variable: delay"));
        var variables=promoted.projection().nodes().stream().filter(node->node.kind().equals("graph-variables")).findFirst().orElseThrow();
        var delayField=variables.fields().stream().filter(field->field.label().equals("delay")).findFirst().orElseThrow();
        assertEquals("/variables/delay/default",delayField.yamlPath());assertEquals("1s",delayField.value());assertTrue(delayField.editable());
        GraphMutationResponse changedDefault=mutations.mutate(request("script","flow",promoted.content(),List.of(
                op(GraphMutationOperation.Type.EDIT_FIELD,delayField.yamlPath(),null,null,null,null,null,null,"2s",null))));
        assertEquals("2s",find(documents.parse(changedDefault.content()).root(),"/variables/delay/default").value());
        GraphMutationResponse renamed=mutations.mutate(request("script","flow",promoted.content(),List.of(
                advanced(GraphMutationOperation.Type.RENAME_VARIABLE,"/variables",null,null,null,null,"delay","pause-delay"))));
        assertTrue(renamed.content().contains("pause-delay:")&&renamed.content().contains("variable: \"pause-delay\""));
        GraphContractException connected=assertThrows(GraphContractException.class,()->mutations.mutate(request("script","flow",renamed.content(),List.of(
                advanced(GraphMutationOperation.Type.CHANGE_VARIABLE_TYPE,"/variables",null,null,null,"integer","pause-delay",null)))));
        assertEquals("VARIABLE_TYPE_CONNECTED",connected.code());
        var getter=renamed.projection().nodes().stream().filter(node->node.title().equals("get-delay")).findFirst().orElseThrow();
        String getterOutput=getter.pins().stream().filter(pin->pin.direction().equals("OUTPUT")).findFirst().orElseThrow().id();
        GraphMutationResponse disconnected=mutations.mutate(request("script","flow",renamed.content(),List.of(
                advanced(GraphMutationOperation.Type.BREAK_ALL_LINKS,null,getterOutput,null,null,null,null,null),
                advanced(GraphMutationOperation.Type.CHANGE_VARIABLE_TYPE,"/variables",null,null,null,"integer","pause-delay",null))));
        assertEquals("integer",find(documents.parse(disconnected.content()).root(),"/variables/pause-delay/type").value());
        GraphContractException used=assertThrows(GraphContractException.class,()->mutations.mutate(request("script","flow",disconnected.content(),List.of(
                advanced(GraphMutationOperation.Type.DELETE_VARIABLE,"/variables",null,null,null,null,"pause-delay",null)))));
        assertEquals("VARIABLE_IN_USE",used.code());
    }

    @Test void insertsAndProjectsTypedComparisonAndBooleanOperatorNodes() {
        String source="content-version: 2\nid: flow\ninputs: {}\noutputs: {}\nvariables: {}\nnodes: {}\nconnections: {}\n";
        GraphMutationResponse comparison=mutate("script","flow",source,
                op(GraphMutationOperation.Type.INSERT,null,null,"/nodes",null,null,"equals","same","integer",null));
        assertTrue(comparison.content().contains("type: equals\n    value-type: integer"));
        var same=comparison.projection().nodes().stream().filter(node->node.title().equals("same")).findFirst().orElseThrow();
        assertEquals(List.of("left","right","result"),same.pins().stream().map(EditorGraphProjection.GraphPin::label).toList());
        assertEquals("boolean",same.pins().getLast().valueType());
        GraphMutationResponse logical=mutate("script","flow",comparison.content(),
                op(GraphMutationOperation.Type.INSERT,null,null,"/nodes",null,null,"or","either",null,null));
        var either=logical.projection().nodes().stream().filter(node->node.title().equals("either")).findFirst().orElseThrow();
        assertTrue(either.pins().stream().allMatch(pin->pin.valueType().equals("boolean")));
    }

    @Test void insertsRunScriptCallsWithTheDroppedTargetSignature() {
        String source="content-version: 2\nid: flow\ninputs: {}\noutputs: {}\nvariables: {}\nnodes: {}\nconnections: {}\n";
        GraphMutationResponse result=mutate("script","flow",source,
                op(GraphMutationOperation.Type.INSERT,null,null,"/nodes",null,null,"run-script","call","target:flow",null));
        assertTrue(result.content().contains("type: run-script\n    script: target:flow\n    inputs: {}"));
    }

    @Test void addingAStartOutputInvalidatesCallersAndProjectsItAsACallInput() {
        String target = "content-version: 2\nid: target\ninputs: {}\noutputs:\n"
                + "  message: { type: text }\nvariables: {}\nnodes: {}\nconnections: {}\n";
        String caller = "content-version: 2\nid: caller\ndisplay-name: Caller\non-click:\n"
                + "  variables: {}\n  nodes:\n    call: { type: run-script, script: target, inputs: {} }\n"
                + "    show: { type: message, text: fallback }\n  connections:\n"
                + "    returned-message: { from: call.message, to: show.text }\n";
        List<ContentFile> files = List.of(file("scripts/target.yml", target), file("npcs/caller.yml", caller));
        GraphMutationOperation add = advanced(GraphMutationOperation.Type.ADD_SCRIPT_PARAMETER,
                "/inputs", null, null, "message", "text", null, null);
        GraphMutationRequest request = new GraphMutationRequest(EditorGraphProjection.VERSION,
                "scripts/target.yml", "script", "target", "", target, sha(target), files, List.of(add));

        GraphMutationResponse result = mutations.mutate(request);

        assertTrue(result.affectedResourceIds().contains("npc:caller"));
        EditorGraphProjection callerProjection = projections.project(new GraphProjectionRequest(
                "npcs/caller.yml", "npc", "caller", "", caller, sha(caller), result.rawFiles()), List.of(), "none");
        EditorGraphProjection.GraphNode call = callerProjection.nodes().stream()
                .filter(node -> node.kind().equals("script-call")).findFirst().orElseThrow();
        assertTrue(call.pins().stream().anyMatch(pin -> pin.direction().equals("INPUT")
                && pin.channel().equals("DATA") && pin.label().equals("message") && pin.valueType().equals("text")));
        assertTrue(call.pins().stream().anyMatch(pin -> pin.direction().equals("OUTPUT")
                && pin.channel().equals("DATA") && pin.label().equals("message") && pin.valueType().equals("text")));
        EditorGraphProjection.GraphEdge returned = callerProjection.edges().stream()
                .filter(edge -> edge.label().equals("returned-message")).findFirst().orElseThrow();
        assertTrue(call.pins().stream().filter(pin -> pin.direction().equals("OUTPUT") && pin.label().equals("message"))
                .anyMatch(pin -> pin.id().equals(returned.sourcePinId())));

        EditorGraphProjection.GraphNode event = callerProjection.nodes().stream()
                .filter(node -> node.kind().equals("event") && node.title().equals("On Click")).findFirst().orElseThrow();
        String eventExec = event.pins().stream().filter(pin -> pin.direction().equals("OUTPUT")
                && pin.label().equals("exec")).findFirst().orElseThrow().id();
        String callExec = call.pins().stream().filter(pin -> pin.direction().equals("INPUT")
                && pin.label().equals("exec")).findFirst().orElseThrow().id();
        GraphMutationRequest connectRequest = new GraphMutationRequest(EditorGraphProjection.VERSION,
                "npcs/caller.yml", "npc", "caller", "", caller, sha(caller), result.rawFiles(),
                List.of(op(GraphMutationOperation.Type.CONNECT, null, null, null,
                        eventExec, callExec, null, "start-call", null, null)));
        GraphMutationResponse connected = mutations.mutate(connectRequest);
        assertTrue(connected.content().contains("start-call:")
                && connected.content().contains("$event.exec")
                && connected.content().contains("call.exec"));
    }

    @Test void reorderNeverTrustsAnArrayIndexWithoutStableParentAndNeighborPorts() {
        GraphContractException rejected = assertThrows(GraphContractException.class, () -> mutate(
                "behavior", "demo:walk", behavior(),
                op(GraphMutationOperation.Type.REORDER, "/root/children/1", "/root/children/0",
                        "/root/children", null, null, null, null, null, 0)));
        assertEquals("REORDER_PARENT_PORT_REQUIRED", rejected.code());
    }

    @Test void reconnectAtomicallyReplacesExplicitGraphTargetAndUnsignedInsertFailsClosed() {
        String source = "content-version: 2\nid: demo:talk\nstart: first\nnodes:\n  first:\n    graph:\n      variables: {}\n      nodes:\n        line: { type: say, text: Hi }\n        second: { type: end-dialogue }\n        third: { type: end-dialogue }\n      connections:\n        enter: { from: $event.exec, to: line.exec }\n        finish: { from: line.success, to: second.exec }\n";
        EditorGraphProjection graph = projections.project(projectionRequest("dialogue", "demo:talk", source));
        EditorGraphProjection.GraphEdge edge = graph.edges().stream().filter(value -> value.label().equals("finish"))
                .findFirst().orElseThrow();
        EditorGraphProjection.GraphNode third = graph.nodes().stream().filter(node -> node.title().equals("third"))
                .findFirst().orElseThrow();
        String target = third.pins().stream().filter(pin -> pin.direction().equals("INPUT")
                && pin.label().equals("exec")).findFirst().orElseThrow().id();
        GraphMutationOperation reconnect = new GraphMutationOperation(GraphMutationOperation.Type.RECONNECT,
                edge.sourceYamlPath(), null, null, edge.sourcePinId(), target, null, null, null, null, null,
                "reconnect-1", null, edge.id(), null, null, null, null, List.of());
        GraphMutationResponse result = mutate("dialogue", "demo:talk", source, reconnect);
        assertTrue(result.content().contains("to: \"third.exec\""));
        assertFalse(result.content().contains("to: second.exec"));
        assertEquals(1, result.appliedOperationCount());

        GraphContractException unsigned = assertThrows(GraphContractException.class, () -> mutations.mutate(
                request("behavior", "demo:walk", behavior(), List.of(op(GraphMutationOperation.Type.INSERT,
                        null, null, "/root/children", null, null, "extension-action", "unsigned",
                        "vendor:unknown", 0)))));
        assertEquals("UNSIGNED_EXTENSION_SCHEMA", unsigned.code());
    }

    @Test void connectingExecutionOutputAtomicallyReplacesItsPreviousWire() {
        String source = "content-version: 2\nid: demo:talk\nstart: first\nnodes:\n  first:\n    graph:\n"
                + "      variables: {}\n      nodes:\n        line: { type: say, text: Hi }\n"
                + "        second: { type: end-dialogue }\n        third: { type: end-dialogue }\n"
                + "      connections:\n        enter: { from: $event.exec, to: line.exec }\n"
                + "        finish: { from: line.success, to: second.exec }\n";
        EditorGraphProjection graph = projections.project(projectionRequest("dialogue", "demo:talk", source));
        var line = graph.nodes().stream().filter(node -> node.title().equals("line")).findFirst().orElseThrow();
        var third = graph.nodes().stream().filter(node -> node.title().equals("third")).findFirst().orElseThrow();
        String output = line.pins().stream().filter(pin -> pin.label().equals("success")).findFirst().orElseThrow().id();
        String input = third.pins().stream().filter(pin -> pin.label().equals("exec")).findFirst().orElseThrow().id();
        GraphMutationResponse result = mutate("dialogue", "demo:talk", source,
                op(GraphMutationOperation.Type.CONNECT, null, null, null, output, input,
                        null, "replacement", null, null));
        assertFalse(result.content().contains("finish:"));
        assertTrue(result.content().contains("replacement:") && result.content().contains("to: third.exec"));
        assertEquals(1, result.projection().edges().stream().filter(edge -> edge.sourcePinId().equals(output)).count());
    }

    private static void assertGolden(GraphMutationResponse result, String label) {
        assertTrue(result.document().valid(), label);
        assertTrue(result.content().startsWith("# golden\n"), label);
        assertEquals(1, result.content().split("sentinel: !vendor keep", -1).length - 1, label + "\n" + result.content());
    }

    private GraphMutationResponse mutate(String kind, String id, String source, GraphMutationOperation... operations) {
        List<EditorSchemaDocument> schemas = Arrays.stream(operations)
                .filter(operation -> operation.nodeKind() != null && operation.nodeKind().startsWith("extension-")
                        && operation.value() != null && operation.value().matches("[a-z0-9_.-]+:[a-z0-9_.-]+"))
                .map(operation -> new EditorSchemaDocument(extensionContentType(operation.nodeKind()), operation.value(),
                        operation.value().substring(0, operation.value().indexOf(':')), "1", "{}", sha("{}")))
                .toList();
        return mutations.mutate(request(kind, id, source, List.of(operations)), schemas, "signed-test");
    }
    private static String extensionContentType(String nodeKind) {
        return switch (nodeKind) {
            case "extension-action" -> "behavior-action";
            case "extension-condition" -> "behavior-condition";
            case "extension-objective" -> "objective";
            default -> "command";
        };
    }
    private static GraphMutationRequest request(String kind, String id, String source,
                                                List<GraphMutationOperation> operations) {
        return new GraphMutationRequest(EditorGraphProjection.VERSION, path(kind), kind, id,
                "", source, sha(source), List.of(), operations);
    }
    private static GraphProjectionRequest projectionRequest(String kind, String id, String source) {
        return new GraphProjectionRequest(path(kind), kind, id, "",
                source, sha(source), List.of());
    }
    private static String path(String kind) { return kind.equals("script") ? "scripts/test.yml" : kind + "s/test.yml"; }
    private static GraphMutationOperation op(GraphMutationOperation.Type type, String yamlPath,
                                             String targetYamlPath, String parentYamlPath,
                                             String sourcePin, String targetPin, String nodeKind,
                                             String key, String value, Integer index) {
        return new GraphMutationOperation(type, yamlPath, targetYamlPath, parentYamlPath,
                sourcePin, targetPin, nodeKind, key, value, index, null);
    }
    private static GraphMutationOperation advanced(GraphMutationOperation.Type type,String parentYamlPath,
                                                    String sourcePin,String targetPin,String key,String valueType,
                                                    String parameterName,String newName){
        return new GraphMutationOperation(type,null,null,parentYamlPath,sourcePin,targetPin,null,key,null,null,null,
                UUID.randomUUID().toString(),null,null,null,null,null,null,List.of(),valueType,null,null,parameterName,newName);
    }
    private static GraphMutationOperation pinDefault(String pinId, String value) {
        return new GraphMutationOperation(GraphMutationOperation.Type.SET_PIN_DEFAULT,
                null, null, null, null, pinId, null, null, value, null, null);
    }
    private record GraphNodePair(EditorGraphProjection.GraphNode source,EditorGraphProjection.GraphNode target){}
    private GraphMutationOperation reorder(String source, String sourcePath, String neighborPath, boolean before) {
        EditorGraphProjection graph = projections.project(projectionRequest("behavior", "demo:walk", source));
        var neighbor = graph.nodes().stream().filter(node -> neighborPath.equals(node.yamlPath()))
                .findFirst().orElseThrow();
        var sourceNode = graph.nodes().stream().filter(node -> sourcePath.equals(node.yamlPath()))
                .findFirst().orElseThrow();
        String port = neighbor.pins().stream().filter(pin -> "INPUT".equals(pin.direction()))
                .findFirst().orElseThrow().id();
        String parentPort = graph.ports().stream().filter(pin -> "/root/children".equals(pin.yamlPath())
                && "+ child".equals(pin.label())).findFirst().orElseThrow().id();
        return new GraphMutationOperation(GraphMutationOperation.Type.REORDER, sourcePath, neighborPath,
                "/root/children", null, null, null, null, null, null, null,
                UUID.randomUUID().toString(), sourceNode.id(), null, parentPort,
                before ? port : null, before ? null : port, sourceNode.range(), List.of());
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
