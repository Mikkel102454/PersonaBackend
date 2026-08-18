package nu.miguel.personabackend.graph;

import com.fasterxml.jackson.annotation.JsonAlias;
import nu.miguel.persona.editor.protocol.ContentFile;
import java.util.List;

public record GraphMutationRequest(
        int graphVersion,
        @JsonAlias("filePath")
        String path,
        String resourceKind,
        String resourceId,
        @JsonAlias("rootYamlPath")
        String yamlPath,
        String content,
        @JsonAlias("expectedContentDigest")
        String expectedDigest,
        List<ContentFile> projectFiles,
        List<GraphMutationOperation> operations,
        String requestId,
        String resourceIdentity,
        String expectedProjectRevision) {
    public GraphMutationRequest {
        projectFiles = projectFiles == null ? List.of() : List.copyOf(projectFiles);
        operations = operations == null ? List.of() : List.copyOf(operations);
    }
    public GraphMutationRequest(int graphVersion, String path, String resourceKind, String resourceId,
                                String yamlPath, String content, String expectedDigest,
                                List<ContentFile> projectFiles, List<GraphMutationOperation> operations) {
        this(graphVersion, path, resourceKind, resourceId, yamlPath, content, expectedDigest,
                projectFiles, operations, null, null, null);
    }
}
