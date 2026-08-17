package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;
import java.util.List;

public record ProjectExtractScriptRequest(List<ContentFile> files, String expectedRevision,
                                          String sourcePath, String sourceYamlPath, String scriptId) {
    public ProjectExtractScriptRequest { files = files == null ? List.of() : List.copyOf(files); }
}
