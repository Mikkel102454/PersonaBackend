package nu.miguel.personabackend.snapshot;

import nu.miguel.persona.editor.protocol.EditorMetadataSnapshot;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/editor/sessions/{sessionId}/metadata")
public final class EditorMetadataController {
    private final EditorMetadataService metadata;public EditorMetadataController(EditorMetadataService metadata){this.metadata=metadata;}
    @PutMapping public EditorMetadataSnapshot upload(@PathVariable UUID sessionId,@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,@RequestBody EditorMetadataSnapshot value){return metadata.store(sessionId,bearer(authorization),value);}
    @GetMapping public EditorMetadataSnapshot download(@PathVariable UUID sessionId,@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization){return metadata.read(sessionId,bearer(authorization));}
    private static String bearer(String value){if(value==null||!value.startsWith("Bearer "))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Missing bearer lease");return value.substring(7);}
}
