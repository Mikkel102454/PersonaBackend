package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;

import java.util.List;

public record ProjectExportRequest(List<ContentFile> files) {
    public ProjectExportRequest {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
