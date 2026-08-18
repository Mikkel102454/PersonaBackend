package nu.miguel.personabackend.session;

import nu.miguel.persona.editor.protocol.Capability;
import nu.miguel.persona.editor.protocol.EditorScope;
import nu.miguel.persona.editor.protocol.SessionRestrictions;

import java.security.PublicKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class EditorSession {
    private final UUID id;
    private final UUID installationId;
    private final PublicKey installationKey;
    private final String initiatorId;
    private final String initiatorName;
    private final EditorScope scope;
    private final SessionRestrictions restrictions;
    private final Set<Capability> requestedCapabilities;
    private Set<Capability> grantedCapabilities;
    private final Instant expiresAt;
    private final String verificationCodeHash;
    private final String pluginLeaseHash;
    private final AtomicLong pluginSequence = new AtomicLong();
    private final AtomicLong browserSequence = new AtomicLong();
    private int attempts;
    private boolean verified;
    private String browserKey;
    private String browserDescription;
    private String browserLeaseHash;
    private volatile Instant pluginActivity = Instant.now();
    private volatile Instant browserActivity = Instant.now();

    EditorSession(UUID id, UUID installationId, PublicKey installationKey, String initiatorId,
                  String initiatorName, EditorScope scope, Instant expiresAt,
                  SessionRestrictions restrictions,
                  Set<Capability> requestedCapabilities, String verificationCodeHash, String pluginLeaseHash) {
        this.id = id;
        this.installationId = installationId;
        this.installationKey = installationKey;
        this.initiatorId = initiatorId;
        this.initiatorName = initiatorName;
        this.scope = scope;
        this.restrictions = restrictions == null ? SessionRestrictions.UNRESTRICTED : restrictions;
        this.requestedCapabilities = Set.copyOf(requestedCapabilities);
        this.grantedCapabilities = requestedCapabilities.contains(Capability.CONTENT_VIEW)
                ? Set.of(Capability.CONTENT_VIEW) : Set.of();
        this.expiresAt = expiresAt;
        this.verificationCodeHash = verificationCodeHash;
        this.pluginLeaseHash = pluginLeaseHash;
    }

    public UUID id() { return id; }
    public UUID installationId() { return installationId; }
    public PublicKey installationKey() { return installationKey; }
    public String initiatorId() { return initiatorId; }
    public String initiatorName() { return initiatorName; }
    public EditorScope scope() { return scope; }
    public SessionRestrictions restrictions() { return restrictions; }
    public Instant expiresAt() { return expiresAt; }
    public String verificationCodeHash() { return verificationCodeHash; }
    public String pluginLeaseHash() { return pluginLeaseHash; }
    public synchronized int recordFailedAttempt() { return ++attempts; }
    public synchronized boolean verified() { return verified; }
    public synchronized void verify(String browserKey, String browserDescription, String browserLeaseHash) {
        this.browserKey = browserKey;
        this.browserDescription = browserDescription;
        this.browserLeaseHash = browserLeaseHash;
        this.verified = true;
    }
    public synchronized String browserKey() { return browserKey; }
    public synchronized String browserDescription() { return browserDescription; }
    public synchronized String browserLeaseHash() { return browserLeaseHash; }
    public boolean acceptPluginSequence(long sequence) { return accept(pluginSequence, sequence); }
    public boolean acceptBrowserSequence(long sequence) { return accept(browserSequence, sequence); }
    public void touchPlugin() { pluginActivity = Instant.now(); }
    public void touchBrowser() { browserActivity = Instant.now(); }
    public boolean pluginIdle(Instant cutoff) { return pluginActivity.isBefore(cutoff); }
    public boolean browserIdle(Instant cutoff) { return browserActivity.isBefore(cutoff); }
    private boolean accept(AtomicLong counter, long sequence) {
        if (sequence <= 0) return false;
        while (true) {
            long previous = counter.get();
            if (sequence <= previous) return false;
            if (counter.compareAndSet(previous, sequence)) return true;
        }
    }
    public Set<Capability> requestedCapabilities() { return requestedCapabilities; }
    public synchronized Set<Capability> capabilities() { return grantedCapabilities; }
    public synchronized void grant(Set<Capability> capabilities) {
        if (!requestedCapabilities.containsAll(capabilities))
            throw new IllegalArgumentException("Cannot grant capabilities that were not requested");
        LinkedHashSet<Capability> updated = new LinkedHashSet<>(grantedCapabilities);
        updated.addAll(capabilities);
        grantedCapabilities = Set.copyOf(updated);
    }
    public synchronized void revokeElevatedCapabilities() {
        grantedCapabilities = requestedCapabilities.contains(Capability.CONTENT_VIEW)
                ? Set.of(Capability.CONTENT_VIEW) : Set.of();
    }
}
