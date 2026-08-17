package nu.miguel.personabackend.graph;

import nu.miguel.persona.editor.protocol.ContentFile;
import java.util.List;

public record GraphMutationRequest(
        int graphVersion,
        String path,
        String resourceKind,
        String resourceId,
        String yamlPath,
        String content,
        String expectedDigest,
        List<ContentFile> projectFiles,
        List<GraphMutationOperation> operations) {
    public GraphMutationRequest {
        projectFiles = projectFiles == null ? List.of() : List.copyOf(projectFiles);
        operations = operations == null ? List.of() : List.copyOf(operations);
    }
}
