package nu.miguel.personabackend.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import java.util.stream.Stream;

class YamlDocumentServiceTest {
    private final YamlDocumentService documents = new YamlDocumentService();

    @Test void movesAndDuplicatesWholeListBranchesWithoutReserializingCustomYaml(){String yaml="nodes:\n  # retained comment\n  - id: first\n    extension-owned: future-value\n  - id: second\n    extension-owned: other-value\n";YamlDocumentResponse moved=documents.structure(new YamlStructureRequest(yaml,YamlStructureRequest.Operation.MOVE_BEFORE,"/nodes/1","/nodes/0"));assertTrue(moved.valid(),moved.content());assertTrue(moved.content().indexOf("id: second")<moved.content().indexOf("id: first"),moved.content());assertTrue(moved.content().contains("# retained comment"),moved.content());assertTrue(moved.content().contains("extension-owned: future-value"),moved.content());YamlDocumentResponse duplicated=documents.structure(new YamlStructureRequest(moved.content(),YamlStructureRequest.Operation.DUPLICATE_AFTER,"/nodes/1",null));assertTrue(duplicated.valid(),duplicated.content());assertTrue(duplicated.content().contains("duplicate-"),duplicated.content());}

    @Test void insertsTypedListAndMappingTemplatesWithoutRewritingExistingYaml(){String behavior="# keep\nchildren:\n  - id: existing\n    type: action\n    action: set-visible\n";YamlDocumentResponse inserted=documents.insert(new YamlInsertRequest(behavior,"/children","- id: generated\n  type: condition\n  condition: chance\n  chance: 0.5"));assertTrue(inserted.valid(),inserted.content());assertTrue(inserted.content().contains("# keep"));assertTrue(inserted.content().contains("id: generated"));String dialogue="nodes:\n  greeting:\n    script:\n      - type: end-dialogue\n";YamlDocumentResponse node=documents.insertField(new YamlMappingInsertRequest(dialogue,"/nodes","next","script:\n  - type: say\n    text: Hello"));assertTrue(node.valid(),node.content());assertTrue(node.content().contains("  next:\n    script:"),node.content());}

    @Test void extractsACompleteBranchIntoAStandaloneSubtreeAndLeavesAReference(){String source="id: demo:root\nscope: player\nroot:\n  id: root\n  type: sequence\n  children:\n    - id: branch\n      type: sequence\n      children:\n        - id: leaf\n          type: wait\n          duration: 1s\n";YamlExtractResponse result=documents.extractSubtree(new YamlExtractRequest(source,"/root/children/0","demo:branch","player"));assertTrue(result.source().valid(),result.source().content());assertTrue(result.source().content().contains("type: subtree\n      subtree: demo:branch"),result.source().content());assertTrue(result.extractedContent().contains("root:\n  id: branch\n  type: sequence\n  children:\n    - id: leaf"),result.extractedContent());}

    @Test void exposesTypedRangesForMappingsSequencesAndAliases() {
        String source = "# retained header\ndefaults: &defaults\n  enabled: true # retained inline\n"
                + "extension-owned:\n  future-field: 12\nitems:\n  - *defaults\n";

        YamlDocumentResponse parsed = documents.parse(source);

        assertTrue(parsed.valid());
        assertEquals(source, parsed.content());
        YamlDocumentNode future = find(parsed.root(), "/extension-owned/future-field");
        assertNotNull(future);
        assertEquals("integer", future.kind());
        assertEquals("12", source.substring(future.startOffset(), future.endOffset()));
        assertEquals("alias", find(parsed.root(), "/items/0").kind());
    }

    @Test void scalarEditIsDeterministicAndPreservesCommentsOrderingAndUnknownData() {
        String source = "# author 📚\nid: test:actor\nextension-owned:\n  label: old # keep me\n  future: {x: 1}\nname: End\n";

        YamlDocumentResponse first = documents.edit(new YamlEditRequest(
                source, "/extension-owned/label", "O'Brien"));
        YamlDocumentResponse second = documents.edit(new YamlEditRequest(
                source, "/extension-owned/label", "O'Brien"));

        assertTrue(first.valid());
        assertEquals(first.content(), second.content());
        assertEquals("# author 📚\nid: test:actor\nextension-owned:\n  label: \"O'Brien\" # keep me\n"
                + "  future: {x: 1}\nname: End\n", first.content());
    }

    @Test void reportsPreciseSyntaxErrorsAndRejectsUnsafeEditsAndOversizeDocuments() {
        YamlDocumentResponse invalid = documents.parse("root:\n  - valid\n broken: value\n");
        assertFalse(invalid.valid());
        assertFalse(invalid.diagnostics().isEmpty());
        assertTrue(invalid.diagnostics().getFirst().line() >= 2);
        assertTrue(invalid.diagnostics().getFirst().column() >= 1);

        assertThrows(ResponseStatusException.class, () -> documents.edit(
                new YamlEditRequest("root: [unterminated\n", "/root", "x")));
        assertThrows(ResponseStatusException.class, () -> documents.parse("x".repeat(YamlDocumentService.MAX_DOCUMENT_BYTES + 1)));
    }

    @Test void canonicalizesTypedScalarValuesWithoutChangingSurroundingYaml() {
        String source = "enabled: false\ncount: 001\nratio: 1.20\nnothing: null\n";
        YamlDocumentResponse enabled = documents.edit(new YamlEditRequest(source, "/enabled", "true"));
        YamlDocumentResponse count = documents.edit(new YamlEditRequest(enabled.content(), "/count", "42"));
        YamlDocumentResponse ratio = documents.edit(new YamlEditRequest(count.content(), "/ratio", "2.500"));

        assertEquals("enabled: true\ncount: 42\nratio: 2.5\nnothing: null\n", ratio.content());
    }

    @Test void safelyEscapesMultilineVisualStrings() {
        YamlDocumentResponse edited = documents.edit(new YamlEditRequest(
                "message: old\nnext: retained\n", "/message", "line one\nline \"two\""));

        assertTrue(edited.valid());
        assertEquals("message: \"line one\\nline \\\"two\\\"\"\nnext: retained\n", edited.content());
        assertEquals("line one\nline \"two\"", find(edited.root(), "/message").value());
    }

    @ParameterizedTest(name = "visual/source round trip: {0}")
    @MethodSource("contentFixtures")
    void roundTripsEveryContentFamilyAndExtensionData(String family, String yaml, String path, String replacement) {
        YamlDocumentResponse parsed = documents.parse(yaml);
        assertTrue(parsed.valid(), family);
        assertEquals(yaml, parsed.content(), family);

        YamlDocumentResponse edited = documents.edit(new YamlEditRequest(yaml, path, replacement));

        assertTrue(edited.valid(), family);
        assertTrue(edited.content().startsWith("# retained " + family + "\n"), family);
        assertTrue(edited.content().contains("x-future: &future {kept: true}"), family);
        assertTrue(edited.content().contains("future-copy: *future"), family);
        assertEquals(replacement, find(edited.root(), path).value(), family);
    }

    private static Stream<Arguments> contentFixtures() {
        return Stream.of(
                fixture("behavior", "id: demo:tree\nscope: player\nroot:\n  id: wait\n  type: wait\n  duration: 1s\n", "/root/duration", "2s"),
                fixture("npc", "id: demo:npc\ndisplay-name: Old name\n", "/display-name", "New name"),
                fixture("quest", "id: demo:quest\ntitle: Old title\nphases: []\n", "/title", "New title"),
                fixture("dialogue", "id: demo:talk\nstart: hello\nnodes:\n  hello:\n    text: Old text\n", "/nodes/hello/text", "New text"),
                fixture("script", "scripts:\n  greet:\n    - type: message\n      text: Old text\n", "/scripts/greet/0/text", "New text"),
                fixture("extension", "id: demo:extension\nscope: player\nroot:\n  id: custom\n  type: vendor:custom\n  options:\n    label: Old label\n", "/root/options/label", "New label")
        );
    }

    private static Arguments fixture(String family, String body, String path, String replacement) {
        return Arguments.of(family, "# retained " + family + "\nx-future: &future {kept: true}\nfuture-copy: *future\n" + body,
                path, replacement);
    }

    private static YamlDocumentNode find(YamlDocumentNode node, String path) {
        if (node == null || node.path().equals(path)) return node;
        for (YamlDocumentNode child : node.children()) {
            YamlDocumentNode found = find(child, path);
            if (found != null) return found;
        }
        return null;
    }
}
