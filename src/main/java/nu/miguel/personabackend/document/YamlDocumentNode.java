package nu.miguel.personabackend.document;

import java.util.List;

public record YamlDocumentNode(
        String path,
        String key,
        String kind,
        String value,
        String tag,
        boolean editable,
        int keyOffset,
        int keyLine,
        int keyColumn,
        int startOffset,
        int endOffset,
        int startLine,
        int startColumn,
        int endLine,
        int endColumn,
        List<YamlDocumentNode> children
) {
    public YamlDocumentNode { children = children == null ? List.of() : List.copyOf(children); }
}
