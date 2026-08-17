package nu.miguel.persona.editor.protocol;

import java.util.List;

public record DraftPatchRequest(int protocolVersion, String baseRevision, List<DraftPatchFile> changes) {
    public DraftPatchRequest { changes = changes == null ? List.of() : List.copyOf(changes); }
}
