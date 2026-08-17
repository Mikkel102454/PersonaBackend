package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;
import java.util.List;

public record ProjectCreateRequest(List<ContentFile> files, String expectedRevision, String kind,
                                   String id, String path, String template) {}
