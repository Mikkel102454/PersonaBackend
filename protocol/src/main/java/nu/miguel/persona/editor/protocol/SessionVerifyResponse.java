package nu.miguel.persona.editor.protocol;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record SessionVerifyResponse(
        UUID sessionId,
        String browserSocketUrl,
        String browserLeaseToken,
        Set<Capability> capabilities,
        Instant expiresAt
) {}
