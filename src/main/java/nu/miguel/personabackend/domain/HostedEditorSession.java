package nu.miguel.personabackend.domain;

import nu.miguel.persona.editor.protocol.Capability;
import nu.miguel.persona.editor.protocol.EditorScope;
import nu.miguel.persona.editor.protocol.SessionRestrictions;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record HostedEditorSession(UUID id, UUID installationId, String initiatorId, String initiatorName,
                                  EditorScope scope, SessionRestrictions restrictions,
                                  Set<Capability> requestedCapabilities, Instant createdAt, Instant expiresAt,
                                  Instant revokedAt) {
    public HostedEditorSession { requestedCapabilities = Set.copyOf(requestedCapabilities); }
}
