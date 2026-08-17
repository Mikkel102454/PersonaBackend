package nu.miguel.personabackend.security;

import nu.miguel.persona.editor.protocol.Capability;

import java.security.Principal;
import java.util.Set;
import java.util.UUID;

public record EditorPrincipal(UUID sessionId, UUID installationId, Role role,
                              Set<Capability> capabilities) implements Principal {
    public enum Role { PLUGIN, BROWSER }
    public EditorPrincipal { capabilities = Set.copyOf(capabilities); }
    @Override public String getName() { return role.name().toLowerCase() + ':' + sessionId; }
}
