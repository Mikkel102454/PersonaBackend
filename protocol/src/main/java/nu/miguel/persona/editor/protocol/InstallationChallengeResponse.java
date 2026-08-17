package nu.miguel.persona.editor.protocol;

import java.time.Instant;
import java.util.UUID;

public record InstallationChallengeResponse(UUID challengeId,String challenge,Instant expiresAt) {}
