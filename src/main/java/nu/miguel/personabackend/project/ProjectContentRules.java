package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.persona.editor.protocol.ContentProjectRevision;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public final class ProjectContentRules {
    public static final int MAX_FILES = 2_048;
    public static final long MAX_BYTES = 10L * 1_024 * 1_024;
    public static final Set<String> KINDS = Set.of("behavior", "dialogue", "quest", "npc", "script");

    public VerifiedProject verify(List<ContentFile> input, String expectedRevision) {
        if (input == null || input.size() > MAX_FILES) throw bad("PROJECT_FILE_LIMIT", "Project exceeds 2048 YAML files");
        TreeMap<String, ContentFile> files = new TreeMap<>();
        Map<String, String> folded = new HashMap<>();
        long bytes = 0;
        for (ContentFile file : input) {
            if (file == null || !validPath(file.path()) || file.content() == null)
                throw bad("INVALID_PATH", "Project contains an invalid YAML path");
            String collision = folded.putIfAbsent(file.path().toLowerCase(Locale.ROOT), file.path());
            if (collision != null) throw bad("PATH_COLLISION", "Project contains a case-folding path collision");
            if (files.putIfAbsent(file.path(), verifiedFile(file)) != null)
                throw bad("DUPLICATE_PATH", "Project contains a duplicate path");
            bytes += file.content().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_BYTES) throw bad("PROJECT_BYTE_LIMIT", "Project exceeds 10 MiB");
        }
        String revision = ContentProjectRevision.compute(List.copyOf(files.values()));
        if (expectedRevision == null || !MessageDigest.isEqual(revision.getBytes(StandardCharsets.US_ASCII),
                expectedRevision.getBytes(StandardCharsets.US_ASCII)))
            throw conflict("STALE_PROJECT", "The submitted project digest is stale");
        return new VerifiedProject(files, revision, bytes);
    }

    public String safePath(String kind, String id) {
        requireKindAndId(kind, id);
        if (kind.equals("script")) return "scripts.yml";
        String name = id.substring(id.indexOf(':') + 1).replaceAll("[^a-z0-9_.-]", "-");
        return switch (kind) {
            case "behavior" -> "behaviors/" + name + ".yml";
            case "dialogue" -> "dialogues/" + name + ".yml";
            case "quest" -> "quests/" + name + ".yml";
            case "npc" -> "npcs/" + name + ".yml";
            default -> throw bad("INVALID_KIND", "Unsupported content kind");
        };
    }

    public void requireKindAndId(String kind, String id) {
        if (!KINDS.contains(kind)) throw bad("INVALID_KIND", "Unsupported content kind");
        String pattern = kind.equals("script") ? "[a-z0-9][a-z0-9_.-]{0,127}"
                : "[a-z0-9][a-z0-9_.-]{0,62}:[a-z0-9][a-z0-9_.-]{0,62}";
        if (id == null || !id.matches(pattern))
            throw bad("INVALID_ID", kind.equals("script")
                    ? "Script IDs use lowercase letters, digits, dot, underscore, or hyphen"
                    : "Content IDs must be lowercase namespaced IDs such as village:guide");
    }

    public void requireRequestedPath(String kind, String id, String path) {
        if (!Objects.equals(safePath(kind, id), path))
            throw bad("INVALID_PATH", "The requested path does not match the safe path for this content ID");
    }

    public ContentFile file(String path, String content) {
        return new ContentFile(path, sha256(content), content);
    }

    private ContentFile verifiedFile(ContentFile file) {
        String digest = sha256(file.content());
        if (file.sha256() == null || !MessageDigest.isEqual(digest.getBytes(StandardCharsets.US_ASCII),
                file.sha256().getBytes(StandardCharsets.US_ASCII)))
            throw bad("INVALID_DIGEST", "Content digest does not match " + file.path());
        return file;
    }

    private static boolean validPath(String path) {
        if (path == null || path.isBlank() || path.length() > 240 || path.startsWith("/") || path.contains("\\")
                || path.contains("\0") || !(path.endsWith(".yml") || path.endsWith(".yaml"))) return false;
        return Arrays.stream(path.split("/", -1)).noneMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."));
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static ResponseStatusException bad(String code, String message) {
        return new ProjectOperationException(HttpStatus.BAD_REQUEST, code, message, null, null);
    }
    static ResponseStatusException conflict(String code, String message) {
        return new ProjectOperationException(HttpStatus.CONFLICT, code, message, null, null);
    }

    public record VerifiedProject(TreeMap<String, ContentFile> files, String revision, long bytes) {}
}
