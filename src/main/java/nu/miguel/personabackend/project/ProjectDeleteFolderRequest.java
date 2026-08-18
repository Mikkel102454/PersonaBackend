package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;
import java.util.List;

public record ProjectDeleteFolderRequest(List<ContentFile> files,String expectedRevision,String expectedManifestDigest,String folder) {}
