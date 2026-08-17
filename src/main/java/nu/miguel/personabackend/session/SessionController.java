package nu.miguel.personabackend.session;

import jakarta.validation.Valid;
import nu.miguel.persona.editor.protocol.*;
import nu.miguel.personabackend.relay.RelayHub;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/editor/sessions")
public final class SessionController {
    private final SessionService sessions;
    private final RelayHub relay;

    public SessionController(SessionService sessions, RelayHub relay) { this.sessions = sessions; this.relay = relay; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionCreateResponse create(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,@Valid @RequestBody SessionCreateRequest request) {
        return sessions.create(request,bearer(authorization));
    }

    @PostMapping("/installation-challenges")
    public InstallationChallengeResponse challenge(@Valid @RequestBody InstallationChallengeRequest request){return sessions.challenge(request);}

    @PostMapping("/installation-challenges/prove")
    public InstallationChallengeProofResponse prove(@Valid @RequestBody InstallationChallengeProof request){return sessions.prove(request);}

    @PostMapping("/{sessionId}/verify")
    public SessionVerifyResponse verify(@PathVariable UUID sessionId,
                                        @Valid @RequestBody SessionVerifyRequest request) {
        if (!relay.connected(nu.miguel.personabackend.relay.RelaySocketHandler.Role.PLUGIN, sessionId))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The Persona server is not connected to this editor session");
        return sessions.verify(sessionId, request);
    }

    @GetMapping("/{sessionId}/status")
    public EditorSessionStatus status(@PathVariable UUID sessionId,
                                      @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        String lease = bearer(authorization);
        try { return sessions.statusForPlugin(sessionId, lease); }
        catch (ResponseStatusException error) {
            if (error.getStatusCode() != HttpStatus.UNAUTHORIZED) throw error;
            return sessions.statusForBrowser(sessionId, lease);
        }
    }

    @PutMapping("/{sessionId}/capabilities")
    public EditorSessionStatus trust(@PathVariable UUID sessionId,
                                     @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                     @Valid @RequestBody CapabilityGrantRequest request) {
        return sessions.grant(sessionId, bearer(authorization), request);
    }

    @DeleteMapping("/{sessionId}/capabilities")
    public EditorSessionStatus revokeCapabilities(@PathVariable UUID sessionId,
                                                  @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return sessions.revokeCapabilities(sessionId, bearer(authorization));
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID sessionId,
                       @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        sessions.revoke(sessionId, bearer(authorization));
        relay.closeSession(sessionId, CloseStatus.NORMAL.withReason("Session revoked"));
    }

    private static String bearer(String value) {
        if (value == null || !value.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer lease");
        return value.substring(7);
    }
}
