package nu.miguel.personabackend.project;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ProjectImportServiceTest {
    private final ProjectImportService imports = new ProjectImportService();

    @Test void importsYamlVerbatimWithoutRemovingCommentsOrUnknownFields() {
        String yaml = "# author comment\nid: test:actor\nextension-owned:\n  future: true\n";
        var project = imports.importFiles(List.of(new MockMultipartFile(
                "files", "actor.yml", "application/yaml", yaml.getBytes(StandardCharsets.UTF_8))));

        assertEquals(1, project.files().size());
        assertEquals("actor.yml", project.files().getFirst().path());
        assertEquals(yaml, project.files().getFirst().content());
        assertTrue(project.warnings().isEmpty());
    }

    @Test void importsNestedYamlFromZipAndReportsIgnoredFiles() throws Exception {
        byte[] archive = zip(Map.of(
                "project/behaviors/tree.yml", "id: test:tree\n",
                "project/readme.txt", "not content"));
        var project = imports.importFiles(List.of(new MockMultipartFile(
                "files", "project.zip", "application/zip", archive)));

        assertEquals(List.of("project/behaviors/tree.yml"),
                project.files().stream().map(file -> file.path()).toList());
        assertEquals(1, project.warnings().size());
        assertTrue(project.revision().matches("[0-9a-f]{64}"));
    }

    @Test void rejectsZipTraversalAndInvalidUtf8() throws Exception {
        byte[] unsafe = zip(Map.of("../outside.yml", "id: bad\n"));
        assertThrows(ResponseStatusException.class, () -> imports.importFiles(List.of(
                new MockMultipartFile("files", "unsafe.zip", "application/zip", unsafe))));
        assertThrows(ResponseStatusException.class, () -> imports.importFiles(List.of(
                new MockMultipartFile("files", "bad.yml", "application/yaml", new byte[]{(byte) 0xc3, 0x28}))));
    }

    private static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
