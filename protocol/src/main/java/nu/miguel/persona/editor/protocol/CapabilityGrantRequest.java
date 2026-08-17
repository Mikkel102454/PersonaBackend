package nu.miguel.persona.editor.protocol;

import java.util.Set;

public record CapabilityGrantRequest(int protocolVersion, Set<Capability> capabilities) {
    public CapabilityGrantRequest {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }
}
