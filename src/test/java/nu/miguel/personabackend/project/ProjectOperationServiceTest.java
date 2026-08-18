package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.persona.editor.protocol.ContentProjectRevision;
import nu.miguel.personabackend.document.YamlDocumentService;
import nu.miguel.personabackend.reference.ProjectReferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ProjectOperationServiceTest {
    private final YamlDocumentService documents = new YamlDocumentService();
    private final ProjectContentRules rules = new ProjectContentRules();
    private final ProjectReferenceService references = new ProjectReferenceService(documents);
    private final ProjectOperationService operations = new ProjectOperationService(rules, references, documents);

    @Test void createsEverySupportedKindFromServerOwnedMinimalTemplates() {
        List<ContentFile> files = List.of();
        for (var item : List.of(
                new Kind("behavior", "test:walk", "behaviors/walk.yml"),
                new Kind("dialogue", "test:hello", "dialogues/hello.yml"),
                new Kind("quest", "test:tour", "quests/tour.yml"),
                new Kind("npc", "test:guide", "npcs/guide.yml"),
                new Kind("script", "welcome", "scripts.yml"))) {
            var result = operations.create(new ProjectCreateRequest(files, revision(files), item.kind,
                    item.id, item.path, "minimal"));
            files = result.files();
            assertTrue(result.affectedPaths().contains(item.path));
            assertTrue(references.analyze(files).declarations().stream()
                    .anyMatch(value -> value.type().equals(item.kind) && value.id().equals(item.id)));
        }
        assertEquals(5, references.analyze(files).declarations().size());
        files.forEach(file -> assertTrue(documents.parse(file.content()).valid(), file.path()));
    }

    @Test void duplicateAndRenameUseNarrowLosslessPatches() {
        String original = "# leading comment\nid: test:guide # keep inline\ndisplay-name: 'Quoted name'\ncustom: &data !custom tagged\nalias: *data\n";
        List<ContentFile> files = List.of(file("npcs/guide.yml", original));
        var duplicated = operations.duplicate(new ProjectDuplicateRequest(files, revision(files), "npc",
                "test:guide", "test:copy", "npcs/copy.yml"));
        String copy = content(duplicated.files(), "npcs/copy.yml");
        assertEquals(original.replace("test:guide", "\"test:copy\""), copy);
        assertTrue(duplicated.warnings().getFirst().contains("original"));

        var renamed = operations.rename(new ProjectRenameApplyRequest(duplicated.files(), duplicated.revision(),
                "npc", "test:copy", "test:renamed", true, "npcs/renamed.yml"));
        assertFalse(renamed.files().stream().anyMatch(value -> value.path().equals("npcs/copy.yml")));
        assertEquals(copy.replace("\"test:copy\"", "\"test:renamed\""),
                content(renamed.files(), "npcs/renamed.yml"));
    }

    @Test void atomicRenameUpdatesDeclarationAndTypedReferences() {
        List<ContentFile> files = List.of(
                file("behaviors/walk.yml", "id: test:walk\nscope: player\nroot: { id: root, type: wait, duration: 1s }\n"),
                file("npcs/guide.yml", "id: test:guide\nplayer-behavior: test:walk\ndisplay-name: Guide\n"));
        var result = operations.rename(new ProjectRenameApplyRequest(files, revision(files), "behavior",
                "test:walk", "test:stroll", true, "behaviors/stroll.yml"));
        assertTrue(content(result.files(), "behaviors/stroll.yml").contains("id: \"test:stroll\""));
        assertTrue(content(result.files(), "npcs/guide.yml").contains("player-behavior: \"test:stroll\""));
        assertTrue(references.analyze(result.files()).references().stream().allMatch(value -> value.resolved()));
    }

    @Test void duplicatesRenamesAndDeletesReusableScriptsWithoutRewritingNeighbors() {
        String source = "# scripts header\ncontent-version: 2\nscripts:\n  first:\n"
                + "    # retain this\n    inputs: {}\n    outputs: {}\n"
                + "    nodes:\n      say: { type: say, text: 'hello' }\n"
                + "    connections:\n      enter: { from: $input.exec, to: say.exec }\n"
                + "      leave: { from: say.success, to: $output.exec }\n"
                + "  untouched:\n    inputs: {}\n    outputs: {}\n    nodes: {}\n    connections: {}\n";
        List<ContentFile> files = List.of(file("scripts.yml", source));
        var duplicate = operations.duplicate(new ProjectDuplicateRequest(files, revision(files), "script",
                "first", "second", "scripts.yml"));
        String withCopy = content(duplicate.files(), "scripts.yml");
        assertTrue(withCopy.startsWith(source));
        assertTrue(withCopy.contains("second:"));
        assertTrue(withCopy.contains("text: 'hello'"));

        var renamed = operations.rename(new ProjectRenameApplyRequest(duplicate.files(), duplicate.revision(),
                "script", "second", "renamed", false, "scripts.yml"));
        String withRename = content(renamed.files(), "scripts.yml");
        assertEquals(withCopy.replace("second:", "renamed:"), withRename);

        var deleted = operations.delete(new ProjectDeleteRequest(renamed.files(), renamed.revision(), "script", "renamed"));
        assertEquals(source, content(deleted.files(), "scripts.yml"));
    }

    @Test void createsFirstReusableScriptInsideAnExistingEmptyMapping() {
        List<ContentFile> files = List.of(file("scripts.yml", "# retained\ncontent-version: 2\nscripts: {}\n"));
        var result = operations.create(new ProjectCreateRequest(files, revision(files), "script",
                "welcome", "scripts.yml", "minimal"));
        assertEquals("# retained\ncontent-version: 2\nscripts: \n  welcome:\n"
                        + "    inputs: {}\n"
                        + "    outputs: {}\n"
                        + "    nodes:\n"
                        + "      pause: { type: wait, duration: 1ms }\n"
                        + "    connections:\n"
                        + "      enter: { from: $input.exec, to: pause.exec }\n"
                        + "      leave: { from: pause.success, to: $output.exec }\n\n",
                content(result.files(), "scripts.yml"));
    }

    @Test void guardedDeleteReportsInboundReferencesAndLeavesCandidateUntouched() {
        List<ContentFile> files = List.of(
                file("dialogues/hello.yml", "id: test:hello\nstart: start\nnodes: { start: { script: [{type: end-dialogue}] } }\n"),
                file("npcs/guide.yml", "id: test:guide\ndialogues:\n  - id: test:hello\n"));
        ProjectOperationException error = assertThrows(ProjectOperationException.class,
                () -> operations.delete(new ProjectDeleteRequest(files, revision(files), "dialogue", "test:hello")));
        assertEquals("INBOUND_REFERENCES", error.code());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> operations.create(new ProjectCreateRequest(files, "0".repeat(64), "npc",
                        "test:other", "npcs/other.yml", "minimal"))).getStatusCode().value());
        assertEquals(2, files.size());
    }

    @Test void rejectsUnsafeIdsPathsDigestsAndCaseFoldingCollisions() {
        List<ContentFile> collision = List.of(file("npcs/A.yml", "id: test:a\n"), file("npcs/a.yml", "id: test:b\n"));
        assertCode("PATH_COLLISION", () -> rules.verify(collision, revision(collision)));
        assertCode("INVALID_ID", () -> operations.create(new ProjectCreateRequest(List.of(), revision(List.of()),
                "npc", "../escape", "npcs/escape.yml", "minimal")));
        assertCode("INVALID_PATH", () -> operations.create(new ProjectCreateRequest(List.of(), revision(List.of()),
                "npc", "test:safe", "../safe.yml", "minimal")));
        ContentFile invalidDigest = new ContentFile("npcs/a.yml", "0".repeat(64), "id: test:a\n");
        assertCode("INVALID_DIGEST", () -> rules.verify(List.of(invalidDigest), revision(List.of(invalidDigest))));
    }

    @Test void atomicallyMovesToCanonicalPathWithoutChangingAnyContentBytes() {
        String content = "# retained\nid: test:guide\ncustom: &value !vendor tagged\nalias: *value\n";
        List<ContentFile> files = List.of(file("npcs/old-name.yaml", content));
        var moved = operations.move(new ProjectMoveRequest(files, revision(files), "npc", "test:guide", "npcs/guide.yml"));
        assertFalse(moved.files().stream().anyMatch(file -> file.path().equals("npcs/old-name.yaml")));
        assertEquals(content, content(moved.files(), "npcs/guide.yml"));
        assertEquals(List.of("npcs/guide.yml", "npcs/old-name.yaml"), moved.affectedPaths());
    }

    @Test void extractsAnExactCommandToAReusableScriptAndLeavesAReferenceInOneAtomicCandidate() {
        String dialogue = "# retained\nid: test:hello\nstart: start\nnodes:\n  start:\n    script:\n"
                + "      - type: say # command comment\n        text: 'Exact text'\n        vendor: !custom tagged\n"
                + "      - type: end-dialogue\n";
        List<ContentFile> files = List.of(file("dialogues/hello.yml", dialogue));
        ProjectOperationResponse result = operations.extractScript(new ProjectExtractScriptRequest(files,
                revision(files), "dialogues/hello.yml", "/nodes/start/script/0", "greeting"));
        String updated = content(result.files(), "dialogues/hello.yml");
        assertTrue(updated.startsWith("# retained\n"));
        assertTrue(updated.contains("- type: run-script\n        script: greeting"));
        assertTrue(updated.contains("- type: end-dialogue"));
        String scripts = content(result.files(), "scripts.yml");
        assertTrue(scripts.contains("greeting:"));
        assertTrue(scripts.contains("type: say # command comment"));
        assertTrue(scripts.contains("text: 'Exact text'"));
        assertTrue(scripts.contains("vendor: !custom tagged"));
        assertTrue(references.analyze(result.files()).references().stream().anyMatch(reference ->
                reference.targetType().equals("script") && reference.targetId().equals("greeting") && reference.resolved()));
    }

    @Test void atomicallyCreatesAndAssignsNpcBehaviorAndDialogueTargets() {
        List<ContentFile> files = List.of(file("npcs/guide.yml", "# keep\nid: test:guide\ndisplay-name: Guide\n"));
        ProjectOperationResponse behavior = operations.createAndAssign(new ProjectCreateAndAssignRequest(files,
                revision(files), "npcs/guide.yml", "npc-player-behavior", "test:walk"));
        assertTrue(content(behavior.files(), "npcs/guide.yml").contains("player-behavior:\n  test:walk"));
        assertTrue(behavior.files().stream().anyMatch(file -> file.path().equals("behaviors/walk.yml")));
        assertTrue(references.analyze(behavior.files()).references().stream().allMatch(reference -> reference.resolved()));

        ProjectOperationResponse dialogue = operations.createAndAssign(new ProjectCreateAndAssignRequest(behavior.files(),
                behavior.revision(), "npcs/guide.yml", "npc-dialogue", "test:hello"));
        assertTrue(content(dialogue.files(), "npcs/guide.yml").contains("dialogues:\n  - id: test:hello"));
        assertTrue(dialogue.files().stream().anyMatch(file -> file.path().equals("dialogues/hello.yml")));
        assertTrue(references.analyze(dialogue.files()).references().stream().allMatch(reference -> reference.resolved()));
    }

    @Test void atomicallyCreatesAndRepairsAnyTypedScalarReference() {
        List<ContentFile> files = List.of(file("dialogues/guide.yml",
                "id: test:guide\nstart: hello\nnodes:\n  hello:\n    script:\n      - type: run-script\n        script: missing-script # keep\n"));
        ProjectOperationResponse result = operations.createAndAssign(new ProjectCreateAndAssignRequest(files,
                revision(files), "dialogues/guide.yml", "typed-reference", "missing-script",
                "/nodes/hello/script/0/script", "script"));
        assertTrue(content(result.files(), "dialogues/guide.yml").contains("script: missing-script # keep"));
        assertTrue(result.files().stream().anyMatch(file -> file.path().equals("scripts.yml")
                && file.content().contains("missing-script:")));
        assertTrue(references.analyze(result.files()).references().stream().allMatch(reference -> reference.resolved()));
    }

    @Test void rejectsAnInvalidCompleteCandidateWithoutReturningPartialChanges() {
        List<ContentFile> files = List.of(file("other/broken.yml", "value: [\n"));
        ProjectOperationException error = assertThrows(ProjectOperationException.class,
                () -> operations.create(new ProjectCreateRequest(files, revision(files), "npc",
                        "test:guide", "npcs/guide.yml", "minimal")));
        assertEquals("INVALID_PROJECT_YAML", error.code());
        assertEquals("other/broken.yml", error.filePath());
        assertEquals(1, files.size());
    }

    private static void assertCode(String expected, Runnable operation) {
        assertEquals(expected, assertThrows(ProjectOperationException.class, operation::run).code());
    }
    private static String content(List<ContentFile> files, String path) {
        return files.stream().filter(file -> file.path().equals(path)).findFirst().orElseThrow().content();
    }
    private static ContentFile file(String path, String content) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
            return new ContentFile(path, digest, content);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static String revision(List<ContentFile> files) { return ContentProjectRevision.compute(files); }
    private record Kind(String kind, String id, String path) {}
}
