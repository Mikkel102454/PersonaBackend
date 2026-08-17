package nu.miguel.personabackend.reference;

import nu.miguel.persona.editor.protocol.ContentFile;
import java.util.List;

public record ProjectReferenceRequest(List<ContentFile> files) {
    public ProjectReferenceRequest { files = files == null ? List.of() : List.copyOf(files); }
}
