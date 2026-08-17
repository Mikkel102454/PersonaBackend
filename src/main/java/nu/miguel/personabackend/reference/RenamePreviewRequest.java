package nu.miguel.personabackend.reference;

import nu.miguel.persona.editor.protocol.ContentFile;
import java.util.List;

public record RenamePreviewRequest(List<ContentFile> files, String type, String currentId, String replacementId) {
    public RenamePreviewRequest { files = files == null ? List.of() : List.copyOf(files); }
}
