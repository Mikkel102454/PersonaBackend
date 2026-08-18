package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public final class ProjectExportService {
    static final int MAX_FILES = 1_024;
    static final long MAX_BYTES = 10L * 1_024 * 1_024;
    private static final long ZIP_TIMESTAMP = 315_532_800_000L; // 1980-01-01, the ZIP epoch.

    public byte[] export(ProjectExportRequest request) {
        if (request == null || request.files().isEmpty() || request.files().size() > MAX_FILES)
            throw bad("An export must contain between 1 and " + MAX_FILES + " YAML files");
        List<PreparedFile> files = validate(request.files());
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                for (PreparedFile file : files) {
                    ZipEntry entry = new ZipEntry(file.path());
                    entry.setTime(ZIP_TIMESTAMP);
                    entry.setComment(null);
                    entry.setExtra(null);
                    zip.putNextEntry(entry);
                    zip.write(file.content());
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not create in-memory project archive", impossible);
        }
    }

    private static List<PreparedFile> validate(List<ContentFile> source) {
        long bytes = 0;
        Set<String> paths = new HashSet<>();
        List<PreparedFile> files = new ArrayList<>(source.size());
        for (ContentFile file : source) {
            if (file == null || !validPath(file.path()) || file.content() == null || file.sha256() == null
                    || !paths.add(file.path()))
                throw bad("Export contains an invalid or duplicate YAML path");
            byte[] content = file.content().getBytes(StandardCharsets.UTF_8);
            bytes += content.length;
            if (bytes > MAX_BYTES) throw bad("Export exceeds 10 MiB");
            if (!MessageDigest.isEqual(hex(digest().digest(content)).getBytes(StandardCharsets.US_ASCII),
                    file.sha256().getBytes(StandardCharsets.US_ASCII)))
                throw bad("Export file digest does not match content");
            files.add(new PreparedFile(file.path(), content));
        }
        files.sort(Comparator.comparing(PreparedFile::path));
        return List.copyOf(files);
    }

    private static boolean validPath(String path) {
        return ProjectPathRules.MANIFEST_PATH.equals(path) || ProjectPathRules.validResourcePath(path);
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    private record PreparedFile(String path, byte[] content) {}
}
