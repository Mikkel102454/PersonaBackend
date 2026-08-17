package nu.miguel.personabackend.domain;

import java.time.Instant;
import java.util.UUID;

public record BrowserIdentity(UUID sessionId, byte[] publicKey, String description, Instant boundAt) {
    public BrowserIdentity { publicKey = publicKey.clone(); }
    @Override public byte[] publicKey() { return publicKey.clone(); }
}
