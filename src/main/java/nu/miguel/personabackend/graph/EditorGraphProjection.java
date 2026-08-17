package nu.miguel.personabackend.graph;

import nu.miguel.personabackend.document.YamlDiagnostic;
import java.util.List;

public record EditorGraphProjection(
        int graphVersion,
        String resourceIdentity,
        String resourceKind,
        String resourceId,
        String filePath,
        String rootYamlPath,
        String contentDigest,
        boolean editable,
        List<GraphNode> nodes,
        List<GraphEdge> edges,
        List<GraphDiagnostic> diagnostics,
        List<String> capabilities) {
    public static final int VERSION = 1;
    public EditorGraphProjection {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }

    public record SourceRange(int startOffset, int endOffset, int startLine, int startColumn,
                              int endLine, int endColumn) {}
    public record GraphField(String id, String label, String yamlPath, SourceRange range,
                             String valueType, String value, boolean editable, boolean required,
                             boolean custom) {}
    public record GraphPin(String id, String nodeId, String direction, String semanticType,
                           String cardinality, boolean required, String label, String yamlPath) {}
    public record GraphNode(String id, String yamlPath, SourceRange range, String kind, String title,
                            String subtitle, List<GraphField> fields, List<GraphPin> pins,
                            List<String> badges, boolean custom, String extensionOwner) {
        public GraphNode {
            fields = fields == null ? List.of() : List.copyOf(fields);
            pins = pins == null ? List.of() : List.copyOf(pins);
            badges = badges == null ? List.of() : List.copyOf(badges);
        }
    }
    public record GraphEdge(String id, String sourcePinId, String targetPinId, String semanticType,
                            String label, String sourceYamlPath, String targetYamlPath,
                            boolean resolved, boolean cyclic) {}
    public record GraphDiagnostic(String code, String severity, String message, String filePath,
                                  String yamlPath, SourceRange range, String nodeId,
                                  String relatedResourceKind, String relatedResourceId) {}
}
