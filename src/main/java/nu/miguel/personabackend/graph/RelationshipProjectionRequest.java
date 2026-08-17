package nu.miguel.personabackend.graph;

import nu.miguel.persona.editor.protocol.ContentFile;
import java.util.List;

public record RelationshipProjectionRequest(List<ContentFile> files, String expectedRevision) {
    public RelationshipProjectionRequest { files = files == null ? List.of() : List.copyOf(files); }
}
