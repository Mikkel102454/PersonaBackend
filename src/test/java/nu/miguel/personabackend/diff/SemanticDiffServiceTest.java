package nu.miguel.personabackend.diff;

import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.personabackend.document.YamlDocumentService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SemanticDiffServiceTest {
    private final SemanticDiffService diffs = new SemanticDiffService(new YamlDocumentService());

    @Test void reportsTypedSemanticChangesAcrossEveryContentFamily() {
        List<ContentFile> before = List.of(
                file("behaviors/a.yml", "id: demo:a\nroot: {duration: 1}\n"),
                file("npcs/a.yml", "id: demo:npc\ndisplay-name: Old\n"),
                file("dialogues/a.yml", "id: demo:talk\nstart: old\n"),
                file("quests/a.yml", "id: demo:quest\ntitle: Old\n"),
                file("scripts.yml", "# old\nscripts: {hello: []}\n"));
        List<ContentFile> after = List.of(
                file("behaviors/a.yml", "id: demo:a\nroot: {duration: 2}\n"),
                file("npcs/a.yml", "id: demo:npc\ndisplay-name: New\n"),
                file("dialogues/a.yml", "id: demo:talk\nstart: new\n"),
                file("quests/a.yml", "id: demo:quest\ntitle: New\n"),
                file("scripts.yml", "# changed comment only\nscripts: {hello: []}\n"));

        SemanticDiffResponse result = diffs.compare(new SemanticDiffRequest(before, after));

        assertEquals(Set.of("behavior", "npc", "dialogue", "quest"), result.changes().stream()
                .map(SemanticDiffEntry::category).collect(Collectors.toSet()));
        SemanticDiffEntry duration = result.changes().stream().filter(item -> item.yamlPath().equals("/root/duration"))
                .findFirst().orElseThrow();
        assertEquals("integer", duration.beforeKind()); assertEquals("1", duration.beforeValue());
        assertEquals("2", duration.afterValue());
        assertTrue(result.changes().stream().noneMatch(item -> item.category().equals("script")),
                "comments are not semantic changes");
    }

    @Test void reportsAddedAndRemovedFilesDeterministically() {
        SemanticDiffResponse result = diffs.compare(new SemanticDiffRequest(
                List.of(file("npcs/old.yml", "id: demo:old\n")),
                List.of(file("npcs/new.yml", "id: demo:new\n"))));
        assertEquals(List.of("FILE_ADDED", "FILE_REMOVED"), result.changes().stream()
                .map(SemanticDiffEntry::change).sorted().toList());
    }
    private static ContentFile file(String path, String content) { return new ContentFile(path, "", content); }
}
