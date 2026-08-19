package nu.miguel.personabackend.graph;

import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.personabackend.document.YamlDocumentService;
import nu.miguel.personabackend.reference.ProjectReferenceService;
import nu.miguel.personabackend.project.ProjectContentRules;
import nu.miguel.persona.editor.protocol.ContentProjectRevision;
import nu.miguel.persona.editor.protocol.EditorSchemaDocument;
import nu.miguel.personabackend.security.QuotaProperties;
import nu.miguel.personabackend.security.RateLimitService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GraphProjectionServiceTest {
    private final YamlDocumentService documents = new YamlDocumentService();
    private final GraphProjectionService projections = new GraphProjectionService(
            documents, new ProjectReferenceService(documents), new ProjectContentRules());

    @Test void scriptProjectionHidesRootContainersButPreservesUnknownYaml() {
        String yaml = "content-version: 2\nid: flow\ninputs: {}\noutputs: {}\nvariables: {}\nnodes: {}\n"
                + "connections: {}\nfuture-root: !vendor tagged\n";
        EditorGraphProjection result = project("scripts/flow.yml", "script", "flow", "", yaml, List.of());
        List<String> customPaths = result.nodes().stream().filter(EditorGraphProjection.GraphNode::custom)
                .map(EditorGraphProjection.GraphNode::yamlPath).toList();
        assertEquals(List.of("/future-root"), customPaths);
    }

    @Test void behaviorProjectionHasStableRangesTypedPinsOrderedEdgesAndCustomFallback() {
        String yaml = "# header\nid: test:walk\nscope: player\nfuture-root: !vendor tagged\nroot:\n  id: root\n  type: sequence\n  children:\n    - id: first\n      type: condition\n      condition: chance\n      chance: 1.0\n    - id: second\n      type: action\n      action: set-visible\n      visible: true\n";
        EditorGraphProjection result = project("behaviors/walk.yml", "behavior", "test:walk", "", yaml, List.of());
        assertEquals(EditorGraphProjection.VERSION, result.graphVersion());
        assertEquals(4, result.nodes().size());
        assertTrue(result.nodes().stream().anyMatch(node -> node.kind().equals("sequence") && node.title().equals("root")));
        assertTrue(result.nodes().stream().filter(node -> node.kind().equals("sequence") && node.title().equals("root"))
                .flatMap(node -> node.fields().stream()).anyMatch(field -> field.label().equals("scope") && field.value().equals("player")));
        assertTrue(result.nodes().stream().anyMatch(node -> node.kind().equals("custom-yaml")
                && node.yamlPath().equals("/future-root") && node.custom()));
        assertEquals(List.of("1", "2"), result.edges().stream().filter(edge -> edge.semanticType().equals("behavior-child"))
                .map(EditorGraphProjection.GraphEdge::label).toList());
        assertTrue(result.ports().stream().allMatch(port -> Set.of("INPUT", "OUTPUT").contains(port.direction())));
        assertTrue(result.ports().stream().allMatch(port -> port.sourceRange() != null));
        assertAllEdgesAddressDeclaredPins(result);
        assertTrue(result.nodes().stream().allMatch(node -> node.range().startOffset() <= node.range().endOffset()));
        assertEquals(result, project("behaviors/walk.yml", "behavior", "test:walk", "", yaml, List.of()));
    }

    @Test void dialogueProjectionIncludesEntriesCommandsTransfersAndAdvisoryDiagnostics() {
        String yaml = "content-version: 2\nid: test:talk\nstart: start\nnodes:\n"
                + "  start:\n    graph:\n      variables: {}\n      nodes:\n        say: { type: say, text: Hello }\n        go: { type: goto, node: loop }\n      connections:\n        enter: { from: $event.exec, to: say.exec }\n        go: { from: say.success, to: go.exec }\n"
                + "  loop:\n    graph:\n      variables: {}\n      nodes:\n        again: { type: goto, node: start }\n      connections: { enter: { from: $event.exec, to: again.exec } }\n"
                + "  orphan:\n    graph:\n      variables: {}\n      nodes:\n        missing: { type: goto, node: missing }\n      connections: { enter: { from: $event.exec, to: missing.exec } }\n";
        EditorGraphProjection result = project("dialogues/talk.yml", "dialogue", "test:talk", "", yaml, List.of());
        assertTrue(result.nodes().stream().anyMatch(node -> node.kind().equals("dialogue-entry")
                && node.badges().contains("start")));
        assertTrue(result.nodes().stream().anyMatch(node -> node.kind().equals("script-say")));
        assertTrue(result.nodes().stream().anyMatch(node -> node.kind().equals("missing-reference")));
        assertTrue(result.diagnostics().stream().anyMatch(issue -> issue.code().equals("MISSING_DESTINATION")));
        assertTrue(result.diagnostics().stream().anyMatch(issue -> issue.code().equals("UNREACHABLE_NODE")));
        assertTrue(result.edges().stream().anyMatch(EditorGraphProjection.GraphEdge::cyclic));
        assertAllEdgesAddressDeclaredPins(result);

        String implicit = "content-version: 2\nid: test:implicit\nstart: start\nnodes:\n  start:\n    graph:\n      variables: {}\n      nodes: { line: { type: say, text: Hi } }\n      connections: { enter: { from: $event.exec, to: line.exec } }\n";
        assertTrue(project("dialogues/implicit.yml", "dialogue", "test:implicit", "", implicit, List.of())
                .nodes().stream().anyMatch(node -> node.kind().equals("script-say")));
    }

    @Test void questNpcAndReusableScriptProjectionsExposeTheirNativeGraphModels() {
        String behavior = "id: test:walk\nscope: player\nroot: { id: root, type: wait, duration: 1s }\n";
        String npc = "content-version: 2\nid: test:guide\ndisplay-name: Guide\nplayer-behavior: test:walk\npresentation: { pose: STANDING, age: adult }\ndialogues:\n  - { id: test:first, priority: 10 }\n  - { id: test:second, priority: 5 }\nanchors:\n  home: { world: world, x: 0, y: 64, z: 0 }\n";
        List<ContentFile> project = List.of(file("behaviors/walk.yml", behavior), file("npcs/guide.yml", npc));
        EditorGraphProjection npcGraph = project("npcs/guide.yml", "npc", "test:guide", "", npc, project);
        assertTrue(npcGraph.nodes().stream().anyMatch(node -> node.kind().equals("npc-configuration")));
        assertTrue(npcGraph.nodes().stream().anyMatch(node -> node.kind().equals("resource-reference")
                && !node.badges().contains("unresolved")));
        assertTrue(npcGraph.nodes().stream().anyMatch(node -> node.kind().equals("npc-anchor")));
        assertEquals(2, npcGraph.nodes().stream().filter(node -> node.kind().equals("dialogue-registration"))
                .map(EditorGraphProjection.GraphNode::id).distinct().count());
        assertEquals(5, npcGraph.nodes().stream().filter(node -> node.kind().equals("event")).count());
        assertEquals(List.of("exec", "player", "npc", "npc-instance", "left-button", "right-button"),
                npcGraph.nodes().stream().filter(node -> node.kind().equals("event") && node.title().equals("On Click"))
                        .findFirst().orElseThrow().pins().stream().map(EditorGraphProjection.GraphPin::label).toList());
        assertTrue(npcGraph.nodes().stream().filter(node -> node.kind().equals("npc-configuration"))
                .flatMap(node -> node.pins().stream()).anyMatch(pin -> pin.label().equals("Display Name")));
        assertTrue(npcGraph.nodes().stream().noneMatch(node -> node.kind().equals("npc-presentation")));

        String quest = "content-version: 2\nid: test:quest\ntitle: Quest\nwhen: { type: chance, chance: 1.0 }\nphases:\n  - id: first\n    objectives:\n      - id: wait\n        type: wait\n        duration: 1s\n    on-start:\n      variables: {}\n      nodes:\n        line: { type: say, text: Started }\n        begin: { type: start-quest, quest: test:quest }\n      connections:\n        enter: { from: $event.exec, to: line.exec }\n        begin: { from: line.success, to: begin.exec }\n    branches:\n      - when: { type: chance, chance: 1.0 }\n        next-phase: second\n  - id: second\n    objectives:\n      - id: visit\n        type: visit-location\n        location: { world: world, x: 0, y: 64, z: 0 }\n";
        EditorGraphProjection questGraph = project("quests/quest.yml", "quest", "test:quest", "", quest, List.of());
        assertEquals(2, questGraph.nodes().stream().filter(node -> node.kind().equals("quest-phase")).count());
        assertTrue(questGraph.nodes().stream().anyMatch(node -> node.kind().equals("quest")
                && node.fields().stream().anyMatch(field -> field.label().equals("title"))));
        assertEquals(2, questGraph.nodes().stream().filter(node -> node.kind().equals("quest-objective")).count());
        assertTrue(questGraph.nodes().stream().anyMatch(node -> node.kind().equals("quest-completion")));
        assertTrue(questGraph.nodes().stream().anyMatch(node -> node.kind().equals("quest-condition")));
        assertTrue(questGraph.nodes().stream().anyMatch(node -> node.kind().equals("quest-branch")));
        assertTrue(questGraph.nodes().stream().anyMatch(node -> node.kind().equals("script-say")
                && node.yamlPath().contains("/on-start/")));
        assertTrue(questGraph.nodes().stream().filter(node -> node.kind().equals("script-start-quest"))
                .flatMap(node -> node.pins().stream()).anyMatch(pin -> pin.channel().equals("DATA")
                        && pin.direction().equals("INPUT") && pin.valueType().equals("quest")));
        assertTrue(questGraph.nodes().stream().anyMatch(node -> node.kind().equals("resource-value")
                && node.subtitle().equals("quest") && node.title().equals("test:quest")));

        String scripts = "content-version: 2\nid: welcome\ninputs: {}\noutputs: {}\nvariables: {}\nnodes:\n  say: { type: say, text: Welcome }\n  pause: { type: wait, duration: 1ms }\nconnections:\n  enter: { from: $input.exec, to: say.exec }\n  next: { from: say.success, to: pause.exec }\n  leave: { from: pause.success, to: $output.exec }\n";
        EditorGraphProjection scriptGraph = project("scripts/welcome.yml", "script", "welcome", "", scripts, List.of());
        assertTrue(scriptGraph.nodes().stream().anyMatch(node -> node.kind().equals("script-input")));
        assertTrue(scriptGraph.nodes().stream().anyMatch(node -> node.kind().equals("script-output")));
        assertTrue(scriptGraph.nodes().stream().anyMatch(node -> node.kind().equals("script-say")));
        assertAllEdgesAddressDeclaredPins(scriptGraph);
    }

    @Test void questProjectionReportsUnreachableCyclesDuplicateIdsAndShadowedBranches() {
        String yaml = "id: test:quest\nphases:\n"
                + "  - id: start\n    branches:\n      - next-phase: loop\n      - next-phase: orphan\n"
                + "  - id: loop\n    branches:\n      - next-phase: start\n"
                + "  - id: never\n    objectives: []\n"
                + "  - id: orphan\n    branches:\n      - next-phase: end\n"
                + "  - id: orphan\n    objectives: []\n";
        EditorGraphProjection graph = project("quests/quest.yml", "quest", "test:quest", "", yaml, List.of());
        assertTrue(graph.diagnostics().stream().anyMatch(issue -> issue.code().equals("IMPOSSIBLE_BRANCH")));
        assertTrue(graph.diagnostics().stream().anyMatch(issue -> issue.code().equals("PHASE_CYCLE")));
        assertTrue(graph.diagnostics().stream().anyMatch(issue -> issue.code().equals("DUPLICATE_PHASE_ID")));
        assertTrue(graph.diagnostics().stream().anyMatch(issue -> issue.code().equals("UNREACHABLE_PHASE")));
        assertTrue(graph.edges().stream().anyMatch(EditorGraphProjection.GraphEdge::cyclic));
    }

    @Test void signedExtensionCommandsExposeNominalInputAndOutputPins() {
        String yaml = "content-version: 2\nid: roll\ninputs: {}\n"
                + "outputs:\n  score: { type: 'vendor:score', required: true }\nvariables: {}\n"
                + "nodes:\n  dice: { type: 'vendor:roll', limit: 4 }\n"
                + "connections:\n  enter: { from: $input.exec, to: dice.exec }\n"
                + "  result: { from: dice.score, to: $output.score }\n"
                + "  leave: { from: dice.success, to: $output.exec }\n";
        String schemaJson = "{\"x-persona-input-pins\":[{\"name\":\"limit\",\"valueType\":\"integer\",\"required\":false,\"default\":6}],"
                + "\"x-persona-output-pins\":[{\"name\":\"score\",\"valueType\":\"vendor:score\",\"required\":true}],"
                + "\"x-persona-value-types\":{\"vendor:score\":{}}}";
        EditorSchemaDocument schema = new EditorSchemaDocument("command", "vendor:roll", "vendor",
                "1", schemaJson, sha(schemaJson));
        EditorGraphProjection graph = projections.project(new GraphProjectionRequest("scripts/roll.yml", "script",
                "roll", "", yaml, sha(yaml), List.of()), List.of(schema), "catalog-1");
        EditorGraphProjection.GraphNode command = graph.nodes().stream()
                .filter(node -> node.title().equals("dice")).findFirst().orElseThrow();
        assertTrue(command.pins().stream().anyMatch(pin -> pin.direction().equals("INPUT")
                && pin.channel().equals("DATA") && pin.label().equals("limit") && pin.valueType().equals("integer")));
        assertTrue(command.pins().stream().anyMatch(pin -> pin.direction().equals("OUTPUT")
                && pin.channel().equals("DATA") && pin.label().equals("score") && pin.valueType().equals("vendor:score")));
        assertTrue(graph.edges().stream().anyMatch(edge -> edge.semanticType().equals("data:vendor:score")));
        assertEquals(Set.of("EXECUTION:INPUT", "EXECUTION:OUTPUT", "DATA:INPUT", "DATA:OUTPUT"),
                graph.ports().stream().map(pin -> pin.channel() + ":" + pin.direction()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void playerTargetedCommandsExposeARequiredNonLiteralPlayerInput() {
        String yaml = "content-version: 2\nid: targeted\n"
                + "inputs:\n  target: { type: player, required: true }\noutputs: {}\nvariables: {}\n"
                + "nodes:\n  give: { type: give-item, material: minecraft:stone, amount: 2 }\n"
                + "connections:\n  enter: { from: $input.exec, to: give.exec }\n"
                + "  target: { from: $input.target, to: give.player }\n"
                + "  leave: { from: give.success, to: $output.exec }\n";

        EditorGraphProjection graph = project("scripts/targeted.yml", "script", "targeted", "", yaml, List.of());
        EditorGraphProjection.GraphNode give = graph.nodes().stream()
                .filter(node -> node.title().equals("give")).findFirst().orElseThrow();
        EditorGraphProjection.GraphPin player = give.pins().stream()
                .filter(pin -> pin.label().equals("player")).findFirst().orElseThrow();

        assertEquals("INPUT", player.direction());
        assertEquals("DATA", player.channel());
        assertEquals("player", player.valueType());
        assertTrue(player.required());
        assertFalse(player.literal().editable());
        assertTrue(graph.edges().stream().anyMatch(edge -> edge.targetPinId().equals(player.id())));
        assertTrue(give.pins().stream().filter(pin -> pin.label().equals("material"))
                .allMatch(pin -> pin.literal().editable()));
    }

    @Test void rejectsStaleInvalidAndOversizedGraphRequestsWithStructuredCodes() {
        String yaml = "id: test:a\n";
        GraphContractException stale = assertThrows(GraphContractException.class, () -> projections.project(
                new GraphProjectionRequest("npcs/a.yml", "npc", "test:a", "", yaml, "0".repeat(64), List.of())));
        assertEquals("STALE_CONTENT", stale.code());
        GraphContractException invalid = assertThrows(GraphContractException.class, () -> project(
                "npcs/a.yml", "npc", "test:a", "", "id: [\n", List.of()));
        assertEquals("INVALID_YAML", invalid.code());

        StringBuilder large = new StringBuilder("content-version: 2\nid: huge\ninputs: {}\noutputs: {}\nvariables: {}\nnodes:\n");
        for (int index = 0; index <= GraphProjectionService.MAX_NODES + 8; index++)
            large.append("  n").append(index).append(": { type: stop }\n");
        large.append("connections: {}\n");
        GraphContractException bounded = assertThrows(GraphContractException.class, () -> project(
                "scripts/huge.yml", "script", "huge", "", large.toString(), List.of()));
        assertEquals("GRAPH_NODE_LIMIT", bounded.code());
        assertEquals(413, bounded.getStatusCode().value());

        ContentFile forged = new ContentFile("npcs/forged.yml", "0".repeat(64), "id: test:forged\n");
        GraphContractException projectContext = assertThrows(GraphContractException.class, () -> project(
                "npcs/a.yml", "npc", "test:a", "", yaml, List.of(forged)));
        assertEquals("INVALID_PROJECT_CONTEXT", projectContext.code());
    }

    @Test void relationshipProjectionUsesTypedResolvedUnresolvedAndCyclicServerReferences() {
        List<ContentFile> files = List.of(
                file("dialogues/a.yml", "content-version: 2\nid: test:a\nstart: start\nnodes:\n  start:\n    graph:\n      variables: {}\n      nodes: { jump: { type: goto, dialogue: test:b, node: start } }\n      connections: { enter: { from: $event.exec, to: jump.exec } }\n"),
                file("dialogues/b.yml", "content-version: 2\nid: test:b\nstart: start\nnodes:\n  start:\n    graph:\n      variables: {}\n      nodes: { jump: { type: goto, dialogue: test:a, node: start } }\n      connections: { enter: { from: $event.exec, to: jump.exec } }\n"),
                file("npcs/guide.yml", "content-version: 2\nid: test:guide\ndialogues:\n  - id: test:missing\n"));
        EditorGraphProjection result = projections.relationship(new RelationshipProjectionRequest(
                files, ContentProjectRevision.compute(files)));
        assertEquals("relationship", result.resourceKind());
        assertTrue(result.nodes().stream().anyMatch(node -> node.kind().equals("missing-reference")));
        assertTrue(result.edges().stream().anyMatch(edge -> !edge.resolved()));
        assertTrue(result.edges().stream().anyMatch(EditorGraphProjection.GraphEdge::cyclic));
        assertTrue(result.diagnostics().stream().anyMatch(issue -> issue.code().equals("UNRESOLVED_REFERENCE")));
        assertAllEdgesAddressDeclaredPins(result);
    }

    @Test void graphProjectionEndpointIsRateLimitedPerAuthenticatedSession() {
        GraphProjectionController controller = new GraphProjectionController(projections, new RateLimitService(),
                new QuotaProperties(1, 1, 1, 1, 1, 1, Duration.ofMinutes(1)));
        UUID session = UUID.randomUUID(); String yaml = "id: test:a\ndisplay-name: A\n";
        controller.projection(session, new GraphProjectionRequest("npcs/a.yml", "npc", "test:a", "", yaml,
                sha(yaml), List.of()));
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                controller.projection(session, new GraphProjectionRequest("npcs/a.yml", "npc", "test:a", "", yaml,
                        sha(yaml), List.of())));
        assertEquals(429, error.getStatusCode().value());
    }

    @Test void stableBehaviorPortsAndEdgesSurviveScalarEditsAndReorderWhileUnsignedExtensionsFailClosed() {
        String first = "id: test:tree\nscope: player\nroot:\n  id: root\n  type: sequence\n  children:\n"
                + "    - id: alpha\n      type: wait\n      duration: 1s\n"
                + "    - id: beta\n      type: action\n      action: vendor:wave\n";
        EditorSchemaDocument wave = new EditorSchemaDocument("behavior-action", "vendor:wave", "vendor",
                "1", "{}", sha("{}"));
        EditorGraphProjection original = projections.project(new GraphProjectionRequest("behaviors/tree.yml",
                "behavior", "test:tree", "", first, sha(first), List.of()), List.of(wave), "catalog-1");
        String reordered = first.replace("scope: player", "scope: shared").replace(
                "    - id: alpha\n      type: wait\n      duration: 1s\n    - id: beta\n      type: action\n      action: vendor:wave\n",
                "    - id: beta\n      type: action\n      action: vendor:wave\n    - id: alpha\n      type: wait\n      duration: 1s\n");
        EditorGraphProjection changed = projections.project(new GraphProjectionRequest("behaviors/tree.yml",
                "behavior", "test:tree", "", reordered, sha(reordered), List.of()), List.of(wave), "catalog-1");
        assertEquals(original.ports().stream().map(EditorGraphProjection.GraphPin::id).collect(java.util.stream.Collectors.toSet()),
                changed.ports().stream().map(EditorGraphProjection.GraphPin::id).collect(java.util.stream.Collectors.toSet()));
        assertEquals(original.edges().stream().map(EditorGraphProjection.GraphEdge::id).collect(java.util.stream.Collectors.toSet()),
                changed.edges().stream().map(EditorGraphProjection.GraphEdge::id).collect(java.util.stream.Collectors.toSet()));

        EditorGraphProjection unsigned = project("behaviors/tree.yml", "behavior", "test:tree", "", first, List.of());
        assertTrue(unsigned.nodes().stream().anyMatch(node -> node.custom()
                && node.title().contains("unsigned extension") && node.pins().isEmpty()));
    }

    @Test void stableScriptMappingKeysSurviveScalarEditsAndReorder() {
        String original = "content-version: 2\nid: flow\ninputs: {}\noutputs: {}\nvariables: {}\nnodes:\n  say: { type: say, text: Original }\n  wait: { type: wait, duration: 1s }\nconnections:\n  enter: { from: $input.exec, to: say.exec }\n  next: { from: say.success, to: wait.exec }\n  leave: { from: wait.success, to: $output.exec }\n";
        String changed = "content-version: 2\nid: flow\ninputs: {}\noutputs: {}\nvariables: {}\nnodes:\n  wait: { type: wait, duration: 2s }\n  say: { type: say, text: Changed }\nconnections:\n  enter: { from: $input.exec, to: say.exec }\n  next: { from: say.success, to: wait.exec }\n  leave: { from: wait.success, to: $output.exec }\n";
        EditorGraphProjection first = project("scripts/flow.yml", "script", "flow", "", original, List.of());
        EditorGraphProjection second = project("scripts/flow.yml", "script", "flow", "", changed, List.of());
        Set<String> firstCommands = first.nodes().stream().filter(node -> node.kind().startsWith("script-"))
                .map(EditorGraphProjection.GraphNode::id).collect(java.util.stream.Collectors.toSet());
        Set<String> secondCommands = second.nodes().stream().filter(node -> node.kind().startsWith("script-"))
                .map(EditorGraphProjection.GraphNode::id).collect(java.util.stream.Collectors.toSet());
        assertEquals(firstCommands, secondCommands);
    }

    private EditorGraphProjection project(String path, String kind, String id, String yamlPath,
                                          String content, List<ContentFile> files) {
        return projections.project(new GraphProjectionRequest(path, kind, id, yamlPath, content, sha(content), files));
    }
    private static void assertAllEdgesAddressDeclaredPins(EditorGraphProjection projection) {
        Set<String> pins = new HashSet<>();
        projection.nodes().forEach(node -> node.pins().forEach(pin -> pins.add(pin.id())));
        projection.edges().forEach(edge -> {
            assertTrue(pins.contains(edge.sourcePinId()), edge.sourcePinId());
            assertTrue(pins.contains(edge.targetPinId()), edge.targetPinId());
        });
    }
    private static ContentFile file(String path, String content) { return new ContentFile(path, sha(content), content); }
    private static String sha(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
