package nu.miguel.personabackend.graph;

import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.personabackend.document.YamlDocumentResponse;
import java.util.List;
import java.util.Map;

public record GraphMutationResponse(
        String previousDigest,
        String contentDigest,
        String content,
        YamlDocumentResponse document,
        EditorGraphProjection projection,
        List<String> affectedPaths,
        int appliedOperationCount,
        List<ContentFile> rawFiles,
        List<SourcePatch> patches,
        List<EditorGraphProjection.GraphDiagnostic> diagnostics,
        List<String> affectedResourceIds,
        Map<String, String> identityRemap,
        String projectRevision) {
    public GraphMutationResponse {
        affectedPaths = affectedPaths == null ? List.of() : List.copyOf(affectedPaths);
        rawFiles = rawFiles == null ? List.of() : List.copyOf(rawFiles);
        patches = patches == null ? List.of() : List.copyOf(patches);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        affectedResourceIds = affectedResourceIds == null ? List.of() : List.copyOf(affectedResourceIds);
        identityRemap = identityRemap == null ? Map.of() : Map.copyOf(identityRemap);
    }
    public record SourcePatch(String filePath, int beforeStartOffset, int beforeEndOffset,
                              int afterStartOffset, int afterEndOffset, String before, String after) {}
}
