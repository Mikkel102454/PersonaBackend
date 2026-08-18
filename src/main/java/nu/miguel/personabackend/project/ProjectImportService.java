package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.persona.editor.protocol.ProjectImportResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public final class ProjectImportService {
    static final int MAX_FILES = 1_024;
    static final long MAX_BYTES = 10L * 1_024 * 1_024;
    private static final int MAX_ARCHIVE_ENTRIES = 4_096;

    public ProjectImportResponse importFiles(List<MultipartFile> uploads) {
        if (uploads == null || uploads.isEmpty()) throw bad("Select at least one YAML or ZIP file");
        TreeMap<String, byte[]> content = new TreeMap<>();
        List<String> warnings = new ArrayList<>();
        Counter counter = new Counter();
        try {
            for (MultipartFile upload : uploads) {
                if (upload == null || upload.isEmpty()) continue;
                String name = baseName(upload.getOriginalFilename());
                if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) readZip(upload.getInputStream(), content, warnings, counter);
                else if (yaml(name)) add(content, name, upload.getBytes(), counter);
                else warnings.add("Ignored unsupported file " + name);
            }
        } catch (IOException e) {
            throw bad("Could not read imported project: " + e.getMessage());
        }
        if (content.isEmpty()) throw bad("The import contained no YAML files");
        if (content.keySet().stream().anyMatch(path -> !ProjectPathRules.MANIFEST_PATH.equals(path)
                && !ProjectPathRules.validResourcePath(path)))
            throw bad("Imported YAML must be beneath a fixed content root or be .persona/project.yml");

        List<ContentFile> files = new ArrayList<>();
        MessageDigest revision = digest();
        content.forEach((path, bytes) -> {
            String hash = hex(digest().digest(bytes));
            files.add(new ContentFile(path, hash, utf8(bytes, path)));
            revision.update(path.getBytes(StandardCharsets.UTF_8)); revision.update((byte) 0);
            revision.update(hash.getBytes(StandardCharsets.US_ASCII)); revision.update((byte) 0);
        });
        return new ProjectImportResponse(hex(revision.digest()), files, warnings);
    }

    private static void readZip(InputStream source, Map<String, byte[]> content, List<String> warnings,
                                Counter counter) throws IOException {
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(source, StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (++entries > MAX_ARCHIVE_ENTRIES) throw bad("ZIP contains too many entries");
                if (entry.isDirectory()) continue;
                String path = normalizedPath(entry.getName());
                if (!yaml(path)) { warnings.add("Ignored unsupported ZIP entry " + path); continue; }
                add(content, path, boundedRead(zip, counter), counter, false);
            }
        }
    }

    private static byte[] boundedRead(InputStream input, Counter counter) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        for (int read; (read = input.read(buffer)) >= 0; ) {
            if (read == 0) continue;
            if (counter.bytes + output.size() + read > MAX_BYTES) throw bad("Imported YAML exceeds 10 MiB");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void add(Map<String, byte[]> content, String path, byte[] bytes, Counter counter) {
        add(content, normalizedPath(path), bytes, counter, true);
    }

    private static void add(Map<String, byte[]> content, String path, byte[] bytes, Counter counter, boolean countBytes) {
        if (content.size() >= MAX_FILES) throw bad("Imported project exceeds 1024 YAML files");
        if (countBytes && counter.bytes + bytes.length > MAX_BYTES) throw bad("Imported YAML exceeds 10 MiB");
        utf8(bytes, path);
        if (content.putIfAbsent(path, bytes) != null) throw bad("Duplicate imported path " + path);
        counter.bytes += bytes.length;
    }

    private static String normalizedPath(String raw) {
        if (raw == null || raw.isBlank() || raw.startsWith("/") || raw.startsWith("\\") || raw.contains("\u0000"))
            throw bad("Invalid imported path");
        String path = raw.replace('\\', '/');
        String[] parts = path.split("/");
        if (Arrays.stream(parts).anyMatch(part -> part.isBlank() || part.equals(".") || part.equals("..")))
            throw bad("Unsafe imported path " + raw);
        return String.join("/", parts);
    }

    private static String baseName(String raw) {
        if (raw == null || raw.isBlank()) return "upload";
        String normalized = raw.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }
    private static boolean yaml(String path) {
        return ProjectPathRules.MANIFEST_PATH.equals(path) || ProjectPathRules.validResourcePath(path);
    }
    private static String utf8(byte[] bytes, String path) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw bad(path + " is not valid UTF-8"); }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static final class Counter { private long bytes; }
}
