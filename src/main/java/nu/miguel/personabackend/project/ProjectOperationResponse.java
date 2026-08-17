package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;
import java.util.List;

public record ProjectOperationResponse(String revision, List<ContentFile> files, List<String> affectedPaths,
                                       List<String> warnings) {
    public ProjectOperationResponse {
        files = files == null ? List.of() : List.copyOf(files);
        affectedPaths = affectedPaths == null ? List.of() : List.copyOf(affectedPaths);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
