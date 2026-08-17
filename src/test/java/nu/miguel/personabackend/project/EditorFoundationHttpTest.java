package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;
import org.junit.jupiter.api.Test;
import nu.miguel.personabackend.session.EditorPageController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class EditorFoundationHttpTest {
    @Test void hostedEditorExposesRecoveryHistoryPaletteDiffAndExportControls() throws Exception {
        String page = new EditorPageController().offlineEditor();
        for (String id : java.util.List.of("undo", "redo", "copy", "paste", "palette", "diff",
                "export-all", "export-changed","live-open","live-dialog","live-players","live-behaviors")) assertTrue(page.contains("id=\"" + id + "\""), id);
        String script;
        try (var stream = getClass().getResourceAsStream("/static/editor/app.js")) {
            assertNotNull(stream);
            script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String function : java.util.List.of("restoreHistory", "scheduleRecovery", "renderDiff",
                "openPalette", "exportProject", "loadEditorMetadata", "extensionSchemaFor", "schemaInput",
                "requestCatalog", "receiveCatalogResult","subscribeLive","applyLiveSnapshot","renderLive"))
            assertTrue(script.contains("function " + function), function);
        for(String annotation:java.util.List.of("x-persona-widget","x-persona-catalog"))assertTrue(script.contains(annotation),annotation);
    }

    @Test void exportEndpointReturnsDownloadableZip() throws Exception {
        String content = "# retained\nroot: true\n";
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
        var response = new ProjectExportController(new ProjectExportService()).export(new ProjectExportRequest(
                java.util.List.of(new ContentFile("behaviors/example.yml", sha256, content))));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/zip", response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getContentDisposition().getFilename().contains("persona-project.zip"));
        assertNotNull(response.getBody());
    }
}
