package nu.miguel.personabackend.domain;

import java.time.Instant;
import java.util.UUID;

public record ServerInstallation(UUID id, byte[] publicKey, Instant createdAt, Instant lastSeenAt) {
    public ServerInstallation { publicKey = publicKey.clone(); }
    @Override public byte[] publicKey() { return publicKey.clone(); }
}
