package nu.miguel.persona.editor.protocol;

import java.time.Instant;

public record InstallationChallengeProofResponse(String installationLease,Instant expiresAt) {}
