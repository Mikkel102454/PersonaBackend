package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;
import java.util.List;

/** Atomically relocates a file-backed resource without changing its declaration or bytes. */
public record ProjectMoveRequest(List<ContentFile> files, String expectedRevision, String kind,
                                 String id, String replacementPath) {}
