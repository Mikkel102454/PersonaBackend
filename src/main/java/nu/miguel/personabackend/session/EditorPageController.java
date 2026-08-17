package nu.miguel.personabackend.session;

import nu.miguel.personabackend.relay.RelayHub;
import nu.miguel.personabackend.relay.RelaySocketHandler;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@RestController
public final class EditorPageController {
    private final SessionService sessions;
    private final RelayHub relay;

    public EditorPageController(SessionService sessions, RelayHub relay) {
        this.sessions = sessions;
        this.relay = relay;
    }

    @GetMapping(value = "/editor/session/{sessionId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> editor(@PathVariable UUID sessionId) {
        sessions.require(sessionId);
        if (!relay.connected(RelaySocketHandler.Role.PLUGIN, sessionId)) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "The Persona server is not connected to this editor session");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(new ClassPathResource("templates/editor/index.html"));
    }
}
