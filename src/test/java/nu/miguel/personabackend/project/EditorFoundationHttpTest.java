package nu.miguel.personabackend.project;

import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.personabackend.relay.RelayHub;
import nu.miguel.personabackend.relay.RelaySocketHandler;
import nu.miguel.personabackend.session.EditorSession;
import nu.miguel.personabackend.session.SessionService;
import org.junit.jupiter.api.Test;
import nu.miguel.personabackend.session.EditorPageController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EditorFoundationHttpTest {
    @Test void connectedHostedEditorExposesRecoveryHistoryPaletteAndDiffControls() throws Exception {
        UUID sessionId = UUID.randomUUID();
        SessionService sessions = mock(SessionService.class);
        RelayHub relay = mock(RelayHub.class);
        when(sessions.require(sessionId)).thenReturn(mock(EditorSession.class));
        when(relay.connected(RelaySocketHandler.Role.PLUGIN, sessionId)).thenReturn(true);
        var response = new EditorPageController(sessions, relay).editor(sessionId);
        String page;
        try (var stream = response.getBody().getInputStream()) {
            page = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals("no-store", response.getHeaders().getCacheControl());
        for (String id : java.util.List.of("undo", "redo", "copy", "paste", "palette", "diff",
                "live-open", "live-dialog", "live-players", "live-behaviors",
                "reconnect", "reconnect-now")) assertTrue(page.contains("id=\"" + id + "\""), id);
        assertFalse(page.contains("id=\"export-all\""));
        assertFalse(page.contains("id=\"export-changed\""));
        assertFalse(page.contains("offline"));
        assertFalse(page.contains("id=\"import"));
        String script;
        try (var stream = getClass().getResourceAsStream("/static/editor/app.js")) {
            assertNotNull(stream);
            script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String function : java.util.List.of("restoreHistory", "scheduleRecovery", "renderDiff",
                "openPalette", "loadEditorMetadata", "extensionSchemaFor", "schemaInput",
                "requestCatalog", "receiveCatalogResult","subscribeLive","applyLiveSnapshot","renderLive"))
            assertTrue(script.contains("function " + function), function);
        for(String annotation:java.util.List.of("x-persona-widget","x-persona-catalog"))assertTrue(script.contains(annotation),annotation);
        assertTrue(script.contains("lockWorkspace"));
        assertTrue(script.contains("refreshing authoritative project state"));
        verify(sessions).require(sessionId);
    }

    @Test void editorPageIsUnavailableWhenPersonaPluginIsDisconnected() {
        UUID sessionId = UUID.randomUUID();
        SessionService sessions = mock(SessionService.class);
        RelayHub relay = mock(RelayHub.class);
        when(sessions.require(sessionId)).thenReturn(mock(EditorSession.class));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new EditorPageController(sessions, relay).editor(sessionId));
        assertEquals(503, error.getStatusCode().value());
    }

    @Test void exportEndpointReturnsDownloadableZip() throws Exception {
        String content = "# retained\nroot: true\n";
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
        var response = new ProjectExportController(new ProjectExportService()).export(UUID.randomUUID(), new ProjectExportRequest(
                java.util.List.of(new ContentFile("behaviors/example.yml", sha256, content))));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/zip", response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getContentDisposition().getFilename().contains("persona-project.zip"));
        assertNotNull(response.getBody());
    }
}
