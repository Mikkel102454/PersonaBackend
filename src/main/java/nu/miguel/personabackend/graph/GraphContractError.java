package nu.miguel.personabackend.graph;

/** Safe structured rejection; never includes raw YAML or signed/session material. */
public record GraphContractError(
        String code,
        String message,
        String filePath,
        String yamlPath,
        EditorGraphProjection.SourceRange sourceRange,
        String nodeId,
        String portId,
        String fieldId,
        boolean retryable,
        String currentContentDigest,
        String currentProjectRevision) {}
