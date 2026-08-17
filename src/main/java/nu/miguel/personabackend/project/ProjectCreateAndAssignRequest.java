package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;
import java.util.List;

public record ProjectCreateAndAssignRequest(List<ContentFile> files, String expectedRevision,
                                            String sourcePath, String assignment, String targetId,
                                            String sourceYamlPath, String targetKind) {
    public ProjectCreateAndAssignRequest { files = files == null ? List.of() : List.copyOf(files); }
    public ProjectCreateAndAssignRequest(List<ContentFile> files, String expectedRevision,
                                         String sourcePath, String assignment, String targetId) {
        this(files, expectedRevision, sourcePath, assignment, targetId, null, null);
    }
}
