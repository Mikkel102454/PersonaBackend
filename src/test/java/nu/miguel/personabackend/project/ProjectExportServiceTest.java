package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class ProjectExportServiceTest {
    private final ProjectExportService service = new ProjectExportService();

    @Test void exportsSortedDeterministicReadableArchive() throws Exception {
        ContentFile second = file("quests/z.yml", "# keep\nz: 2\n");
        ContentFile first = file("behaviors/a.yml", "a: &value 1\nb: *value\n");

        byte[] left = service.export(new ProjectExportRequest(List.of(second, first)));
        byte[] right = service.export(new ProjectExportRequest(List.of(first, second)));

        assertArrayEquals(left, right);
        Map<String, String> archive = unzip(left);
        assertEquals(List.of("behaviors/a.yml", "quests/z.yml"), new ArrayList<>(archive.keySet()));
        assertEquals(first.content(), archive.get(first.path()));
        assertEquals(second.content(), archive.get(second.path()));
    }

    @Test void rejectsTraversalDuplicatesAndDigestMismatch() {
        assertBad(new ProjectExportRequest(List.of(file("../secret.yml", "x: 1\n"))));
        ContentFile valid = file("npcs/a.yml", "x: 1\n");
        assertBad(new ProjectExportRequest(List.of(valid, valid)));
        assertBad(new ProjectExportRequest(List.of(new ContentFile("npcs/a.yml", "0".repeat(64), "x: 1\n"))));
    }

    @Test void rejectsEmptyAndOversizedExports() {
        assertBad(new ProjectExportRequest(List.of()));
        String content = "x".repeat((int) ProjectExportService.MAX_BYTES + 1);
        assertBad(new ProjectExportRequest(List.of(file("scripts.yml", content))));
    }

    private void assertBad(ProjectExportRequest request) {
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.export(request))
                .getStatusCode().value());
    }
    private static ContentFile file(String path, String content) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
            return new ContentFile(path, digest, content);
        } catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    private static Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; )
                files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
        return files;
    }
}
