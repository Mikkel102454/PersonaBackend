package nu.miguel.personabackend.snapshot;

import nu.miguel.persona.editor.protocol.ContentSnapshot;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/editor/sessions/{sessionId}/snapshot")
public final class SnapshotController {
    private final SnapshotService snapshots;

    public SnapshotController(SnapshotService snapshots) { this.snapshots = snapshots; }

    @PutMapping
    public ContentSnapshot upload(@PathVariable UUID sessionId,
                                  @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                  @RequestBody ContentSnapshot snapshot) {
        return snapshots.store(sessionId, bearer(authorization), snapshot);
    }

    @GetMapping
    public ContentSnapshot download(@PathVariable UUID sessionId,
                                    @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return snapshots.read(sessionId, bearer(authorization));
    }

    private static String bearer(String value) {
        if (value == null || !value.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer lease");
        return value.substring(7);
    }
}
