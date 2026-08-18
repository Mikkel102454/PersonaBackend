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
                new Kind("script", "welcome", "scripts/welcome.yml"))) {
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
        String source = "# script header\ncontent-version: 2\nid: first\n# retain this\ninputs: {}\noutputs: {}\nvariables: {}\n"
                + "nodes:\n  say: { type: say, text: 'hello' }\n"
                + "connections:\n  enter: { from: $input.exec, to: say.exec }\n"
                + "  leave: { from: say.success, to: $output.exec }\n";
        String untouched = "content-version: 2\nid: untouched\ninputs: {}\noutputs: {}\nvariables: {}\nnodes: {}\nconnections: {}\n";
        List<ContentFile> files = List.of(file("scripts/first.yml", source), file("scripts/untouched.yml", untouched));
        var duplicate = operations.duplicate(new ProjectDuplicateRequest(files, revision(files), "script",
                "first", "second", "scripts/second.yml"));
        String withCopy = content(duplicate.files(), "scripts/second.yml");
        assertTrue(withCopy.startsWith("# script header"));
        assertTrue(withCopy.contains("id: \"second\""));
        assertTrue(withCopy.contains("text: 'hello'"));
        assertEquals(untouched, content(duplicate.files(), "scripts/untouched.yml"));

        var renamed = operations.rename(new ProjectRenameApplyRequest(duplicate.files(), duplicate.revision(),
                "script", "second", "renamed", true, "scripts/renamed.yml"));
        String withRename = content(renamed.files(), "scripts/renamed.yml");
        assertEquals(withCopy.replace("\"second\"", "\"renamed\""), withRename);

        var deleted = operations.delete(new ProjectDeleteRequest(renamed.files(), renamed.revision(), "script", "renamed"));
        assertEquals(source, content(deleted.files(), "scripts/first.yml"));
        assertEquals(untouched, content(deleted.files(), "scripts/untouched.yml"));
    }

    @Test void createsFirstReusableScriptAsAnIndividualFile() {
        List<ContentFile> files = List.of();
        var result = operations.create(new ProjectCreateRequest(files, revision(files), "script",
                "welcome", "scripts/welcome.yml", "minimal"));
        assertTrue(content(result.files(), "scripts/welcome.yml").startsWith("content-version: 2\nid: welcome\n"));
    }

    @Test void guardedDeleteReportsInboundReferencesAndLeavesCandidateUntouched() {
        List<ContentFile> files = List.of(
                file("dialogues/hello.yml", "content-version: 2\nid: test:hello\nstart: start\nnodes:\n  start:\n    graph:\n      variables: {}\n      nodes: { end: { type: end-dialogue } }\n      connections: { enter: { from: $event.exec, to: end.exec } }\n"),
                file("npcs/guide.yml", "content-version: 2\nid: test:guide\ndialogues:\n  - id: test:hello\n"));
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
        assertCode("INVALID_PATH", () -> rules.verify(collision, revision(collision)));
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
        String dialogue = "# retained\ncontent-version: 2\nid: test:hello\nstart: start\nnodes:\n  start:\n    graph:\n"
                + "      variables: {}\n      nodes:\n        say: # command comment\n          type: say\n          text: 'Exact text'\n          vendor: !custom tagged\n"
                + "        end: { type: end-dialogue }\n      connections:\n        enter: { from: $event.exec, to: say.exec }\n        finish: { from: say.success, to: end.exec }\n";
        List<ContentFile> files = List.of(file("dialogues/hello.yml", dialogue));
        ProjectOperationResponse result = operations.extractScript(new ProjectExtractScriptRequest(files,
                revision(files), "dialogues/hello.yml", "/nodes/start/graph/nodes/say", "greeting"));
        String updated = content(result.files(), "dialogues/hello.yml");
        assertTrue(updated.startsWith("# retained\n"));
        assertTrue(updated.contains("type: run-script\n          script: greeting"));
        assertTrue(updated.contains("end: { type: end-dialogue }"));
        String scripts = content(result.files(), "scripts/greeting.yml");
        assertTrue(scripts.contains("id: greeting"));
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
                "content-version: 2\nid: test:guide\nstart: hello\nnodes:\n  hello:\n    graph:\n      variables: {}\n      nodes:\n        call:\n          type: run-script\n          script: missing-script # keep\n          inputs: {}\n      connections: {}\n"));
        ProjectOperationResponse result = operations.createAndAssign(new ProjectCreateAndAssignRequest(files,
                revision(files), "dialogues/guide.yml", "typed-reference", "missing-script",
                "/nodes/hello/graph/nodes/call/script", "script"));
        assertTrue(content(result.files(), "dialogues/guide.yml").contains("script: missing-script # keep"));
        assertTrue(result.files().stream().anyMatch(file -> file.path().equals("scripts/missing-script.yml")
                && file.content().contains("id: missing-script")));
        assertTrue(references.analyze(result.files()).references().stream().allMatch(reference -> reference.resolved()));
    }

    @Test void rejectsAnInvalidCompleteCandidateWithoutReturningPartialChanges() {
        List<ContentFile> files = List.of(file("npcs/broken.yml", "value: [\n"));
        ProjectOperationException error = assertThrows(ProjectOperationException.class,
                () -> operations.create(new ProjectCreateRequest(files, revision(files), "npc",
                        "test:guide", "npcs/guide.yml", "minimal")));
        assertEquals("INVALID_PROJECT_YAML", error.code());
        assertEquals("npcs/broken.yml", error.filePath());
        assertEquals(1, files.size());
    }

    @Test void extractionPromotesCrossingDataWiresToTypedReusableBoundaries() {
        String script = "content-version: 2\nid: flow\ninputs:\n  text: { type: text, required: true }\n"
                + "outputs: {}\nvariables: {}\nnodes:\n  say: { type: say, text: fallback }\n"
                + "  end: { type: stop }\nconnections:\n  enter: { from: $input.exec, to: say.exec }\n"
                + "  message: { from: $input.text, to: say.text }\n"
                + "  finish: { from: say.success, to: end.exec }\n";
        List<ContentFile> files = List.of(file("scripts/flow.yml", script));
        ProjectOperationResponse result = operations.extractScript(new ProjectExtractScriptRequest(files,
                revision(files), "scripts/flow.yml", "/nodes/say", "extracted-say"));
        String caller = content(result.files(), "scripts/flow.yml");
        String extracted = content(result.files(), "scripts/extracted-say.yml");
        assertTrue(caller.contains("say:\n    type: run-script\n    script: extracted-say"));
        assertTrue(caller.contains("message: { from: $input.text, to: say.text }"));
        assertTrue(extracted.contains("inputs:\n  text: { type: text, required: true }"));
        assertTrue(extracted.contains("input-text: { from: $input.text, to: say.text }"));
    }

    @Test void extractionCollapsesASelectionAndPreservesOnlyItsInternalWires() {
        String script = "content-version: 2\nid: flow\ninputs:\n  text: { type: text, required: true }\n"
                + "outputs: {}\nvariables: {}\nnodes:\n  say: { type: say, text: fallback }\n"
                + "  pause: { type: wait, duration: 1 }\n  end: { type: stop }\nconnections:\n"
                + "  enter: { from: $input.exec, to: say.exec }\n"
                + "  message: { from: $input.text, to: say.text }\n"
                + "  leave: { from: say.success, to: pause.exec }\n"
                + "  finish: { from: pause.success, to: end.exec }\n";
        List<ContentFile> files = List.of(file("scripts/flow.yml", script));
        ProjectOperationResponse result = operations.extractScript(new ProjectExtractScriptRequest(files,
                revision(files), "scripts/flow.yml", "/nodes/say", List.of("/nodes/say", "/nodes/pause"),
                "extracted-flow"));
        String caller = content(result.files(), "scripts/flow.yml");
        String extracted = content(result.files(), "scripts/extracted-flow.yml");
        assertTrue(caller.contains("say:\n    type: run-script\n    script: extracted-flow"));
        assertFalse(caller.contains("pause: { type: wait"));
        assertFalse(caller.contains("leave: { from: say.success, to: pause.exec }"));
        assertTrue(caller.contains("finish: { from: \"say.success\", to: end.exec }"), caller);
        assertTrue(extracted.contains("say: { type: say, text: fallback }"));
        assertTrue(extracted.contains("pause: { type: wait, duration: 1 }"));
        assertTrue(extracted.contains("leave: { from: say.success, to: pause.exec }"));
        assertTrue(extracted.contains("enter: { from: $input.exec, to: say.exec }"));
        assertTrue(extracted.contains("leave-2: { from: pause.success, to: $output.exec }"));
    }

    @Test void folderManifestOperationsAreAtomicDigestGuardedAndMoveNestedResources() {
        List<ContentFile> files=List.of();String manifestDigest=ProjectPathRules.sha256("");
        ProjectOperationResponse parent=operations.createFolder(new ProjectCreateFolderRequest(files,revision(files),manifestDigest,"npcs/town"));
        files=parent.files();manifestDigest=ProjectPathRules.manifest(files).digest();
        ProjectOperationResponse child=operations.createFolder(new ProjectCreateFolderRequest(files,parent.revision(),manifestDigest,"npcs/town/shops"));
        files=child.files();
        ProjectOperationResponse created=operations.create(new ProjectCreateRequest(files,child.revision(),"npc","test:merchant","npcs/town/shops/merchant.yml","minimal"));
        manifestDigest=ProjectPathRules.manifest(created.files()).digest();
        ProjectOperationResponse moved=operations.moveFolder(new ProjectMoveFolderRequest(created.files(),created.revision(),manifestDigest,"npcs/town","npcs/village"));
        assertTrue(moved.files().stream().anyMatch(file->file.path().equals("npcs/village/shops/merchant.yml")));
        assertEquals(Set.of("npcs/village","npcs/village/shops"),ProjectPathRules.manifest(moved.files()).folders());
        ProjectDeleteFolderRequest delete=new ProjectDeleteFolderRequest(moved.files(),moved.revision(),ProjectPathRules.manifest(moved.files()).digest(),"npcs/village");
        assertTrue(operations.previewFolderDeletion(delete).allowed());
        ProjectOperationResponse deleted=operations.deleteFolder(delete);
        assertTrue(deleted.files().stream().noneMatch(file->file.path().startsWith("npcs/village/")));
        assertCode("STALE_MANIFEST",()->operations.createFolder(new ProjectCreateFolderRequest(deleted.files(),deleted.revision(),"0".repeat(64),"npcs/stale")));
    }

    @Test void folderDeletePreviewReportsExternalTypedReferences() {
        String manifest=ProjectPathRules.renderManifest(Set.of("dialogues/story"));
        List<ContentFile> files=List.of(file(ProjectPathRules.MANIFEST_PATH,manifest),
                file("dialogues/story/hello.yml","content-version: 2\nid: test:hello\nstart: start\nnodes:\n  start:\n    graph:\n      variables: {}\n      nodes: { end: { type: end-dialogue } }\n      connections: { enter: { from: $event.exec, to: end.exec } }\n"),
                file("npcs/guide.yml","content-version: 2\nid: test:guide\ndialogues:\n  - { id: test:hello }\n"));
        ProjectDeleteFolderRequest request=new ProjectDeleteFolderRequest(files,revision(files),ProjectPathRules.manifest(files).digest(),"dialogues/story");
        ProjectFolderDeletePreview preview=operations.previewFolderDeletion(request);
        assertFalse(preview.allowed());assertEquals(1,preview.blockingReferences().size());
        assertCode("INBOUND_REFERENCES",()->operations.deleteFolder(request));
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
