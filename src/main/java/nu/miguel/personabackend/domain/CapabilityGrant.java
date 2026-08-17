package nu.miguel.personabackend.domain;

import nu.miguel.persona.editor.protocol.Capability;

import java.time.Instant;
import java.util.UUID;

public record CapabilityGrant(UUID sessionId, Capability capability, Instant grantedAt, Instant revokedAt) {}
