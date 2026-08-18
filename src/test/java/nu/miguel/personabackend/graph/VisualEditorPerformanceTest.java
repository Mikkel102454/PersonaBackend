package nu.miguel.personabackend.graph;

import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.persona.editor.protocol.ContentProjectRevision;
import nu.miguel.personabackend.document.YamlDocumentService;
import nu.miguel.personabackend.project.ProjectContentRules;
import nu.miguel.personabackend.reference.ProjectReferenceService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class VisualEditorPerformanceTest {
    private final YamlDocumentService documents = new YamlDocumentService();
    private final ProjectContentRules rules = new ProjectContentRules();
    private final ProjectReferenceService references = new ProjectReferenceService(documents);
    private final GraphProjectionService projections = new GraphProjectionService(documents, references, rules);

    @Test void projectsTwoThousandNodeBehaviourWithinAcceptedServerBudget() {
        StringBuilder yaml = new StringBuilder("id: perf:large\nscope: player\nroot:\n  id: root\n  type: sequence\n  children:\n");
        for (int index = 0; index < 1_999; index++) yaml.append("    - id: node-").append(index)
                .append("\n      type: wait\n      duration: 1s\n");
        String content = yaml.toString();
        GraphProjectionRequest request = new GraphProjectionRequest("behaviors/large.yml", "behavior", "perf:large", "",
                content, sha(content), List.of());
        projections.project(request); // warm class loading and parser tables
        long p95 = percentile95(measure(5, () -> assertEquals(2_000, projections.project(request).nodes().size())));
        assertTrue(p95 <= 1_000, "projection p95 exceeded 1000 ms: " + p95 + " ms");
    }

    @Test void analyzesAcceptedTwoThousandFileProjectWithinBudget() {
        List<ContentFile> files = new ArrayList<>(2_000);
        for (int index = 0; index < 1_000; index++) {
            String id = "perf:dialogue-" + index;
            files.add(file("dialogues/dialogue-" + index + ".yml",
                    "content-version: 2\nid: " + id + "\nstart: start\nnodes:\n  start:\n    graph:\n      variables: {}\n      nodes: { end: { type: end-dialogue } }\n      connections: { enter: { from: $event.exec, to: end.exec } }\n"));
        }
        for (int index = 0; index < 1_000; index++) {
            StringBuilder yaml = new StringBuilder("content-version: 2\nid: perf:npc-").append(index).append("\ndialogues:\n");
            for (int edge = 0; edge < 12; edge++) yaml.append("  - id: perf:dialogue-").append((index + edge) % 1_000).append('\n');
            files.add(file("npcs/npc-" + index + ".yml", yaml.toString()));
        }
        String revision = ContentProjectRevision.compute(files);
        rules.verify(files, revision);
        references.analyze(files); // warm
        long p95 = percentile95(measure(5, () -> {
            var graph = references.analyze(files);
            assertEquals(2_000, graph.declarations().size()); assertEquals(12_000, graph.references().size());
        }));
        assertTrue(p95 <= 1_500, "relationship analysis p95 exceeded 1500 ms: " + p95 + " ms");
    }

    private static long[] measure(int count, Runnable work) {
        long[] values = new long[count];
        for (int index = 0; index < count; index++) {
            long start = System.nanoTime(); work.run();
            values[index] = (System.nanoTime() - start) / 1_000_000;
        }
        return values;
    }
    private static long percentile95(long[] values) {
        Arrays.sort(values); return values[Math.max(0, (int) Math.ceil(values.length * .95) - 1)];
    }
    private static ContentFile file(String path, String content) { return new ContentFile(path, sha(content), content); }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
