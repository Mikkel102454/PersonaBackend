package nu.miguel.personabackend.graph;

import nu.miguel.personabackend.document.YamlDocumentResponse;
import java.util.List;

public record GraphMutationResponse(
        String previousDigest,
        String contentDigest,
        String content,
        YamlDocumentResponse document,
        EditorGraphProjection projection,
        List<String> affectedPaths,
        int appliedOperationCount) {
    public GraphMutationResponse {
        affectedPaths = affectedPaths == null ? List.of() : List.copyOf(affectedPaths);
    }
}
