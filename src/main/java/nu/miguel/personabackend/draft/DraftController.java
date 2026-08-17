package nu.miguel.personabackend.draft;

import nu.miguel.persona.editor.protocol.DraftResponse;
import nu.miguel.persona.editor.protocol.DraftSaveRequest;
import nu.miguel.persona.editor.protocol.DraftPatchRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/editor/sessions/{sessionId}/drafts")
public final class DraftController {
    private final DraftService drafts;

    public DraftController(DraftService drafts) { this.drafts = drafts; }

    @PutMapping("/{draftId}")
    public DraftResponse save(@PathVariable UUID sessionId, @PathVariable UUID draftId,
                              @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                              @RequestBody DraftSaveRequest request) {
        return drafts.save(sessionId, draftId, bearer(authorization), request);
    }

    @PatchMapping("/{draftId}")
    public DraftResponse patch(@PathVariable UUID sessionId, @PathVariable UUID draftId,
                               @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                               @RequestBody DraftPatchRequest request) {
        return drafts.patch(sessionId, draftId, bearer(authorization), request);
    }

    @GetMapping("/{draftId}")
    public DraftResponse read(@PathVariable UUID sessionId, @PathVariable UUID draftId,
                              @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return drafts.read(sessionId, draftId, bearer(authorization));
    }

    @GetMapping
    public List<DraftResponse> list(@PathVariable UUID sessionId,
                                    @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return drafts.list(sessionId, bearer(authorization));
    }

    @DeleteMapping("/{draftId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID sessionId, @PathVariable UUID draftId,
                       @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        drafts.delete(sessionId, draftId, bearer(authorization));
    }

    private static String bearer(String value) {
        if (value == null || !value.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer lease");
        return value.substring(7);
    }
}
