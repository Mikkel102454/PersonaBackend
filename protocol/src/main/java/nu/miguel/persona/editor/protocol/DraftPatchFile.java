package nu.miguel.persona.editor.protocol;

/** A digest-guarded file change. Null content/sha256 deletes; null baseSha256 adds. */
public record DraftPatchFile(String path, String baseSha256, String content, String sha256) {}
