package nu.miguel.personabackend.validation;

import nu.miguel.persona.editor.protocol.ValidationProject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/editor/sessions/{sessionId}/validation")
public final class ValidationController {
    private final ValidationService validation;
    public ValidationController(ValidationService validation) { this.validation = validation; }

    @GetMapping("/{requestId}/project")
    public ValidationProject project(@PathVariable UUID sessionId, @PathVariable UUID requestId,
                                     @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return validation.project(sessionId, requestId, bearer(authorization));
    }

    private static String bearer(String value) {
        if (value == null || !value.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer lease");
        return value.substring(7);
    }
}
