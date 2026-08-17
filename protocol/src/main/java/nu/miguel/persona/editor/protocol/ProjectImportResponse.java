package nu.miguel.persona.editor.protocol;

import java.util.List;

public record ProjectImportResponse(String revision, List<ContentFile> files, List<String> warnings) {
    public ProjectImportResponse {
        files = files == null ? List.of() : List.copyOf(files);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
