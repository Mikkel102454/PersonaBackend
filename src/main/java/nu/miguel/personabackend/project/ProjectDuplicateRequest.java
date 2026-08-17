package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;
import java.util.List;

public record ProjectDuplicateRequest(List<ContentFile> files, String expectedRevision, String kind,
                                      String sourceId, String replacementId, String replacementPath) {}
