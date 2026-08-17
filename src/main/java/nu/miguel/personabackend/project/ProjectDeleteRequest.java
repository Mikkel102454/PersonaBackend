package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;
import java.util.List;

public record ProjectDeleteRequest(List<ContentFile> files, String expectedRevision, String kind, String id) {}
