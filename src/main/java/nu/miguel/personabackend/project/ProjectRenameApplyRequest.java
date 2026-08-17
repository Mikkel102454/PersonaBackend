package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;
import java.util.List;

public record ProjectRenameApplyRequest(List<ContentFile> files, String expectedRevision, String kind,
                                        String currentId, String replacementId, boolean renameFile,
                                        String replacementPath) {}
