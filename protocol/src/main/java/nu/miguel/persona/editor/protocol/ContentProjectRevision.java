package nu.miguel.persona.editor.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Canonical revision of a complete scoped project: sorted path and declared content digests. */
public final class ContentProjectRevision {
    private ContentProjectRevision() {}
    public static String compute(List<ContentFile> files) {
        MessageDigest digest = digest();
        files.stream().sorted(Comparator.comparing(ContentFile::path)).forEach(file -> {
            digest.update(file.path().getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
            digest.update(file.sha256().getBytes(StandardCharsets.US_ASCII)); digest.update((byte) 0);
        });
        return HexFormat.of().formatHex(digest.digest());
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
