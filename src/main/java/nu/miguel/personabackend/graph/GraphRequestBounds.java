package nu.miguel.personabackend.graph;

import nu.miguel.persona.editor.protocol.ContentFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared hard bounds for project context submitted to graph endpoints. */
final class GraphRequestBounds {
    private static final int MAX_FILES = 2_048;
    private static final int MAX_FILE_BYTES = 1_048_576;
    private static final long MAX_PROJECT_BYTES = 10L * 1_024 * 1_024;

    private GraphRequestBounds() {}

    static void requireProjectFiles(List<ContentFile> files, String requestPath, String yamlPath) {
        if (files == null || files.size() > MAX_FILES) invalid(requestPath, yamlPath);
        Set<String> paths = new HashSet<>(), folded = new HashSet<>();
        long bytes = 0;
        for (ContentFile file : files) {
            if (file == null || !validPath(file.path()) || file.content() == null
                    || !paths.add(file.path()) || !folded.add(file.path().toLowerCase(Locale.ROOT)))
                invalid(requestPath, yamlPath);
            int size = file.content().getBytes(StandardCharsets.UTF_8).length;
            bytes += size;
            if (size > MAX_FILE_BYTES || bytes > MAX_PROJECT_BYTES || file.sha256() == null
                    || !MessageDigest.isEqual(sha256(file.content()).getBytes(StandardCharsets.US_ASCII),
                    file.sha256().getBytes(StandardCharsets.US_ASCII))) invalid(requestPath, yamlPath);
        }
    }

    private static boolean validPath(String path) {
        if (path == null || path.isBlank() || path.length() > 240 || path.startsWith("/")
                || path.contains("\\") || path.contains("\0")
                || !(path.endsWith(".yml") || path.endsWith(".yaml"))) return false;
        for (String part : path.split("/", -1)) if (part.isBlank() || part.equals(".") || part.equals("..")) return false;
        return true;
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void invalid(String requestPath, String yamlPath) {
        throw new GraphContractException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "INVALID_PROJECT_CONTEXT", "Graph project context is invalid or exceeds its bounds",
                requestPath, yamlPath);
    }
}
