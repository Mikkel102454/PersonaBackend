package nu.miguel.personabackend.reference;

import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.personabackend.document.YamlDocumentService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectReferenceServiceTest {
    private final ProjectReferenceService references = new ProjectReferenceService(new YamlDocumentService());

    @Test void buildsTypedProjectGraphAndMarksMissingTargets() {
        List<ContentFile> files = List.of(
                file("behaviors/guide.yml", "id: demo:guide\nscope: player\nroot: {id: wait, type: wait, duration: 1s}\n"),
                file("dialogues/welcome.yml", "id: demo:welcome\nstart: hello\nnodes: {hello: {text: Hi}}\n"),
                file("npcs/guide.yml", "id: demo:npc\nplayer-behavior: demo:guide\ndialogues:\n  - id: demo:welcome\n  - id: demo:missing\n"),
                file("scripts.yml", "scripts:\n  greet:\n    - {type: run-script, script: absent}\n"));

        ProjectReferenceGraph graph = references.analyze(files);

        assertTrue(graph.declarations().stream().anyMatch(item -> item.type().equals("npc") && item.id().equals("demo:npc")));
        assertTrue(graph.declarations().stream().anyMatch(item -> item.type().equals("script") && item.id().equals("greet")));
        assertTrue(graph.references().stream().anyMatch(item -> item.targetType().equals("behavior")
                && item.targetId().equals("demo:guide") && item.resolved()));
        assertTrue(graph.references().stream().anyMatch(item -> item.targetType().equals("dialogue")
                && item.targetId().equals("demo:missing") && !item.resolved()));
    }

    @Test void previewsDeclarationAndEveryTypedReferenceWithoutMutatingYaml() {
        String dialogue = "# keep\nid: demo:welcome\nstart: hello\nnodes: {hello: {text: Hi}}\n";
        String npc = "id: demo:npc\ndialogues:\n  - id: demo:welcome\n";
        List<ContentFile> files = List.of(file("dialogues/welcome.yml", dialogue), file("npcs/guide.yml", npc));

        RenamePreview preview = references.preview(new RenamePreviewRequest(files, "dialogue",
                "demo:welcome", "demo:greeting"));

        assertTrue(preview.safe()); assertEquals(2, preview.occurrences().size());
        assertTrue(preview.occurrences().stream().anyMatch(item -> item.role().equals("declaration")));
        assertTrue(preview.occurrences().stream().anyMatch(item -> item.role().equals("reference")));
        assertEquals("# keep\nid: demo:welcome\nstart: hello\nnodes: {hello: {text: Hi}}\n", dialogue);
    }

    private static ContentFile file(String path, String content) { return new ContentFile(path, "", content); }
}
