package nu.miguel.personabackend.security;

import nu.miguel.persona.editor.protocol.Capability;
import nu.miguel.personabackend.session.EditorSession;
import nu.miguel.personabackend.session.SessionService;
import nu.miguel.personabackend.relay.RelayHub;
import nu.miguel.personabackend.relay.RelaySocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EditorLeaseAuthenticationFilterTest {
    @Test void installationChallengeEndpointsDoNotRequireAnExistingSessionLease() throws Exception {
        SessionService sessions=mock(SessionService.class);
        EditorLeaseAuthenticationFilter filter=new EditorLeaseAuthenticationFilter(sessions);
        for(String path:java.util.List.of("/api/v1/editor/sessions/installation-challenges",
                "/api/v1/editor/sessions/installation-challenges/prove")){
            MockHttpServletRequest request=new MockHttpServletRequest("POST",path);request.setRequestURI(path);
            boolean[] invoked={false};filter.doFilter(request,new MockHttpServletResponse(),
                    (ignoredRequest,ignoredResponse)->invoked[0]=true);assertTrue(invoked[0],path);
        }
        verifyNoInteractions(sessions);
    }

    @Test void authenticatesBrowserLeaseWithCapabilityAuthorities() throws Exception {
        UUID id = UUID.randomUUID();
        SessionService sessions = mock(SessionService.class);
        EditorSession editor = mock(EditorSession.class);
        when(editor.id()).thenReturn(id);
        when(editor.installationId()).thenReturn(UUID.randomUUID());
        when(editor.capabilities()).thenReturn(Set.of(Capability.CONTENT_VIEW));
        when(sessions.authenticateBrowser(id, "browser-lease")).thenReturn(editor);
        EditorLeaseAuthenticationFilter filter = new EditorLeaseAuthenticationFilter(sessions);
        MockHttpServletRequest request = request("GET", "/api/v1/editor/sessions/" + id + "/snapshot", "browser-lease");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> observed = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                observed.set(SecurityContextHolder.getContext().getAuthentication()));

        assertEquals(200, response.getStatus());
        assertInstanceOf(EditorPrincipal.class, observed.get().getPrincipal());
        assertTrue(observed.get().getAuthorities().stream().anyMatch(value -> value.getAuthority().equals("ROLE_BROWSER")));
        assertTrue(observed.get().getAuthorities().stream().anyMatch(value -> value.getAuthority().equals("CAP_CONTENT_VIEW")));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test void pluginLeaseCannotAuthenticateForBrowserOnlySnapshotRead() throws Exception {
        UUID id = UUID.randomUUID();
        SessionService sessions = mock(SessionService.class);
        when(sessions.authenticateBrowser(id, "plugin-lease"))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        EditorLeaseAuthenticationFilter filter = new EditorLeaseAuthenticationFilter(sessions);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] invoked = {false};

        filter.doFilter(request("GET", "/api/v1/editor/sessions/" + id + "/snapshot", "plugin-lease"),
                response, (ignoredRequest, ignoredResponse) -> invoked[0] = true);

        assertEquals(401, response.getStatus());
        assertFalse(invoked[0]);
        verify(sessions, never()).authenticatePlugin(any(), any());
    }

    @Test void rejectsEveryBrowserApiWhenPersonaPluginIsDisconnected() throws Exception {
        UUID id = UUID.randomUUID();
        SessionService sessions = mock(SessionService.class);
        RelayHub relay = mock(RelayHub.class);
        EditorSession editor = mock(EditorSession.class);
        when(editor.id()).thenReturn(id);
        when(editor.installationId()).thenReturn(UUID.randomUUID());
        when(editor.capabilities()).thenReturn(Set.of(Capability.CONTENT_VIEW, Capability.DRAFT_EDIT));
        when(sessions.authenticateBrowser(id, "browser")).thenReturn(editor);
        EditorLeaseAuthenticationFilter filter = new EditorLeaseAuthenticationFilter(sessions, relay);
        for (String path : java.util.List.of(
                "/api/v1/editor/sessions/" + id + "/snapshot",
                "/api/v1/editor/sessions/" + id + "/documents/parse",
                "/api/v1/editor/sessions/" + id + "/projects/create",
                "/api/v1/editor/sessions/" + id + "/projects/extract-script",
                "/api/v1/editor/sessions/" + id + "/projects/create-and-assign",
                "/api/v1/editor/sessions/" + id + "/export")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            boolean[] invoked = {false};
            filter.doFilter(request(path.endsWith("snapshot") ? "GET" : "POST", path, "browser"),
                    response, (ignoredRequest, ignoredResponse) -> invoked[0] = true);
            assertEquals(503, response.getStatus(), path);
            assertFalse(invoked[0], path);
        }
        verify(relay, times(6)).connected(RelaySocketHandler.Role.PLUGIN, id);
    }

    @Test void separatesBrowserPublishRequestFromPluginClaimAndResult() throws Exception {
        UUID id = UUID.randomUUID(), publishId = UUID.randomUUID();
        SessionService sessions = mock(SessionService.class); EditorSession editor = mock(EditorSession.class);
        when(editor.id()).thenReturn(id); when(editor.installationId()).thenReturn(UUID.randomUUID());
        when(editor.capabilities()).thenReturn(Set.of(Capability.CONTENT_PUBLISH));
        when(sessions.authenticateBrowser(id, "browser")).thenReturn(editor);
        when(sessions.authenticatePlugin(id, "plugin")).thenReturn(editor);
        EditorLeaseAuthenticationFilter filter = new EditorLeaseAuthenticationFilter(sessions);

        filter.doFilter(request("POST", "/api/v1/editor/sessions/" + id + "/publishes", "browser"),
                new MockHttpServletResponse(), (request, response) -> {});
        filter.doFilter(request("POST", "/api/v1/editor/sessions/" + id + "/publishes/claim", "plugin"),
                new MockHttpServletResponse(), (request, response) -> {});
        filter.doFilter(request("POST", "/api/v1/editor/sessions/" + id + "/publishes/confirm", "plugin"),
                new MockHttpServletResponse(), (request, response) -> {});
        filter.doFilter(request("POST", "/api/v1/editor/sessions/" + id + "/publishes/" + publishId + "/result", "plugin"),
                new MockHttpServletResponse(), (request, response) -> {});

        verify(sessions).authenticateBrowser(id, "browser");
        verify(sessions, times(3)).authenticatePlugin(id, "plugin");
    }

    @Test void separatesMetadataUploadAndDownloadLeases() throws Exception {
        UUID id=UUID.randomUUID();SessionService sessions=mock(SessionService.class);EditorSession editor=mock(EditorSession.class);
        when(editor.id()).thenReturn(id);when(editor.installationId()).thenReturn(UUID.randomUUID());when(editor.capabilities()).thenReturn(Set.of(Capability.CONTENT_VIEW));
        when(sessions.authenticatePlugin(id,"plugin")).thenReturn(editor);when(sessions.authenticateBrowser(id,"browser")).thenReturn(editor);
        EditorLeaseAuthenticationFilter filter=new EditorLeaseAuthenticationFilter(sessions);
        filter.doFilter(request("PUT","/api/v1/editor/sessions/"+id+"/metadata","plugin"),new MockHttpServletResponse(),(request,response)->{});
        filter.doFilter(request("GET","/api/v1/editor/sessions/"+id+"/metadata","browser"),new MockHttpServletResponse(),(request,response)->{});
        verify(sessions).authenticatePlugin(id,"plugin");verify(sessions).authenticateBrowser(id,"browser");
    }

    private static MockHttpServletRequest request(String method, String path, String lease) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.addHeader("Authorization", "Bearer " + lease);
        return request;
    }
}
