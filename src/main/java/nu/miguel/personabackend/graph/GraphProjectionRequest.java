package nu.miguel.personabackend.graph;

import nu.miguel.persona.editor.protocol.ContentFile;
import java.util.List;

public record GraphProjectionRequest(String path, String resourceKind, String resourceId,
                                     String yamlPath, String content, String expectedDigest,
                                     List<ContentFile> projectFiles) {
    public GraphProjectionRequest {
        projectFiles = projectFiles == null ? List.of() : List.copyOf(projectFiles);
    }
}
