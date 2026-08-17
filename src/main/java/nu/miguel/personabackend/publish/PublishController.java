package nu.miguel.personabackend.publish;

import nu.miguel.persona.editor.protocol.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/editor/sessions/{sessionId}/publishes")
public final class PublishController {
    private final PublishService publishes;
    public PublishController(PublishService publishes) { this.publishes = publishes; }

    @PostMapping
    public ResponseEntity<PublishCreateResponse> request(@PathVariable UUID sessionId,
                                         @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                         @RequestBody PublishCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(publishes.request(sessionId, bearer(authorization), request));
    }
    @PostMapping("/confirm")
    public PublishProject confirm(@PathVariable UUID sessionId,
                                  @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                  @RequestBody PublishConfirmRequest request) {
        return publishes.confirm(sessionId, bearer(authorization), request);
    }
    @PostMapping("/{publishId}/result")
    public PublishStatusResponse complete(@PathVariable UUID sessionId, @PathVariable UUID publishId,
                                          @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                          @RequestBody PublishApplyResult result) {
        return publishes.complete(sessionId, publishId, bearer(authorization), result);
    }
    @GetMapping("/{publishId}")
    public PublishStatusResponse status(@PathVariable UUID sessionId, @PathVariable UUID publishId,
                                        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return publishes.status(sessionId, publishId, bearer(authorization));
    }
    @GetMapping("/{publishId}/rollback-project")
    public RollbackProject beginRollback(@PathVariable UUID sessionId, @PathVariable UUID publishId,
                                         @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return publishes.beginRollback(sessionId, publishId, bearer(authorization));
    }
    @PostMapping("/{publishId}/rollback-result")
    public PublishStatusResponse completeRollback(@PathVariable UUID sessionId, @PathVariable UUID publishId,
                                                  @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                                  @RequestBody RollbackApplyResult result) {
        return publishes.completeRollback(sessionId, publishId, bearer(authorization), result);
    }
    private static String bearer(String value) {
        if (value == null || !value.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer lease");
        return value.substring(7);
    }
}
