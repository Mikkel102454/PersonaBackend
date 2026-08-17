package nu.miguel.persona.editor.protocol;

import java.util.List;

public record DraftSaveRequest(int protocolVersion, String baseRevision, List<ContentFile> files) {
    public DraftSaveRequest {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
