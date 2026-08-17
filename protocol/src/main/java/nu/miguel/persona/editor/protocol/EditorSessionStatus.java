package nu.miguel.persona.editor.protocol;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record EditorSessionStatus(
        UUID sessionId,
        String initiatorId,
        String initiatorName,
        EditorScope scope,
        SessionRestrictions restrictions,
        Set<Capability> requestedCapabilities,
        Set<Capability> grantedCapabilities,
        boolean browserVerified,
        String browserDescription,
        Instant expiresAt
) {
    public EditorSessionStatus {
        restrictions = restrictions == null ? SessionRestrictions.UNRESTRICTED : restrictions;
        requestedCapabilities = Set.copyOf(requestedCapabilities);
        grantedCapabilities = Set.copyOf(grantedCapabilities);
    }
}
