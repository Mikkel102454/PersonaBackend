package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;
import java.util.List;

public record ProjectExtractScriptRequest(List<ContentFile> files, String expectedRevision,
                                          String sourcePath, String sourceYamlPath,
                                          List<String> sourceYamlPaths, String scriptId) {
    public ProjectExtractScriptRequest {
        files = files == null ? List.of() : List.copyOf(files);
        sourceYamlPaths = sourceYamlPaths == null ? List.of() : List.copyOf(sourceYamlPaths);
    }

    public ProjectExtractScriptRequest(List<ContentFile> files, String expectedRevision,
                                       String sourcePath, String sourceYamlPath, String scriptId) {
        this(files, expectedRevision, sourcePath, sourceYamlPath, List.of(), scriptId);
    }
}
