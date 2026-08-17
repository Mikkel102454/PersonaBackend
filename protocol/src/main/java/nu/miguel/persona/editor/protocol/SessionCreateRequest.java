package nu.miguel.persona.editor.protocol;

import java.util.UUID;
import java.util.Set;

public record SessionCreateRequest(
        int protocolVersion,
        UUID installationId,
        String installationPublicKey,
        String initiatorId,
        String initiatorName,
        EditorScope scope,
        SessionRestrictions restrictions,
        Set<Capability> requestedCapabilities,
        long issuedAt,
        String nonce,
        String signature
) {
    public SessionCreateRequest {
        restrictions = restrictions == null ? SessionRestrictions.UNRESTRICTED : restrictions;
        requestedCapabilities = requestedCapabilities == null ? Set.of() : Set.copyOf(requestedCapabilities);
    }

    public String signingInput() {
        return protocolVersion + "\n" + installationId + "\n" + installationPublicKey + "\n"
                + initiatorId + "\n" + initiatorName + "\n" + scope + "\n" + restrictions.signingValue() + "\n"
                + requestedCapabilities.stream().sorted().map(Enum::name).toList() + "\n" + issuedAt + "\n" + nonce;
    }
}
