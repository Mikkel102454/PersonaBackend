package nu.miguel.persona.editor.protocol;

import java.time.Instant;
import java.util.UUID;

public record SessionCreateResponse(
        UUID sessionId,
        String editorUrl,
        String verificationCode,
        String pluginSocketUrl,
        String pluginLeaseToken,
        Instant expiresAt
) {}
