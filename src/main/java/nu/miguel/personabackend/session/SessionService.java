package nu.miguel.personabackend.session;

import nu.miguel.persona.editor.protocol.*;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import nu.miguel.personabackend.security.QuotaProperties;
import nu.miguel.personabackend.security.RateLimitService;
import nu.miguel.personabackend.storage.HostedMetadataStore;
import nu.miguel.personabackend.storage.InMemoryHostedMetadataStore;
import nu.miguel.personabackend.domain.*;
import nu.miguel.personabackend.storage.ExpiringStateStore;
import nu.miguel.personabackend.storage.InMemoryExpiringStateStore;
import nu.miguel.personabackend.audit.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public final class SessionService {
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final SecureRandom random = new SecureRandom();
    private final EditorProperties properties;
    private final RateLimitService limits;
    private final QuotaProperties quotas;
    private final Map<UUID, EditorSession> sessions = new ConcurrentHashMap<>();
    private final HostedMetadataStore metadata;
    private final ExpiringStateStore state;
    private final AuditService audit;
    private static final Duration CHALLENGE_TTL=Duration.ofMinutes(2),INSTALLATION_LEASE_TTL=Duration.ofMinutes(5);

    public SessionService(EditorProperties properties) {
        this(properties, new RateLimitService(), QuotaProperties.defaults(), new InMemoryHostedMetadataStore(),
                new InMemoryExpiringStateStore(), null);
    }

    @Autowired
    public SessionService(EditorProperties properties, RateLimitService limits, QuotaProperties quotas,
                          HostedMetadataStore metadata, ExpiringStateStore state, AuditService audit) {
        this.properties = properties; this.limits = limits; this.quotas = quotas; this.metadata = metadata;
        this.state = state;
        this.audit = audit == null ? new AuditService(metadata, new ObjectMapper()) : audit;
    }

    public SessionCreateResponse create(SessionCreateRequest request) {
        cleanup();
        if (request.installationId() == null || request.scope() == null || request.initiatorId() == null
                || request.initiatorName() == null || request.nonce() == null || request.nonce().length() < 16)
            throw bad("Missing or invalid signed session fields");
        if (request.protocolVersion() != Protocol.VERSION)
            throw bad("Unsupported protocol version");
        Set<Capability> requested = request.requestedCapabilities();
        if (!EnumSet.allOf(Capability.class).containsAll(requested)
                || !requested.contains(Capability.CONTENT_VIEW)
                || requested.contains(Capability.DRAFT_EDIT) && !requested.contains(Capability.CONTENT_VIEW))
            throw bad("Unsupported or inconsistent requested capabilities");
        if (Math.abs(Instant.now().toEpochMilli() - request.issuedAt()) > properties.requestClockSkew().toMillis())
            throw bad("Signed request is outside the allowed clock window");
        PublicKey suppliedKey = publicKey(request.installationPublicKey());
        if (!verify(suppliedKey, request.signingInput(), request.signature()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid installation signature");
        limits.check("session-create", request.installationId().toString(),
                quotas.sessionCreatesPerInstallation(), quotas.window());
        Optional<byte[]> pinnedKey = metadata.installationKey(request.installationId());
        if (pinnedKey.isPresent() && !MessageDigest.isEqual(pinnedKey.get(), suppliedKey.getEncoded()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Installation identity changed");
        Instant now = Instant.now();
        try { metadata.registerInstallation(new ServerInstallation(request.installationId(), suppliedKey.getEncoded(), now, now)); }
        catch (IllegalStateException changed) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Installation identity changed");
        }
        String replayKey = request.installationId() + ":" + request.nonce();
        if (!state.putIfAbsent("nonce:" + replayKey, "used", properties.requestClockSkew()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Signed request was already used");

        UUID id = UUID.randomUUID();
        String code = randomCode(12);
        String pluginLease = randomToken();
        Instant expiresAt = now.plus(properties.sessionLifetime());
        EditorSession session = new EditorSession(id, request.installationId(), suppliedKey,
                request.initiatorId(), request.initiatorName(), request.scope(), expiresAt,
                request.restrictions(),
                requested, hash(code), hash(pluginLease));
        sessions.put(id, session);
        state.put(verificationKey(id), hash(code), stateTtl(expiresAt));
        state.put(pluginLeaseKey(id), hash(pluginLease), stateTtl(expiresAt));
        metadata.createSession(new HostedEditorSession(id, request.installationId(), request.initiatorId(),
                request.initiatorName(), request.scope(), request.restrictions(), requested, now, expiresAt, null));
        if (session.capabilities().contains(Capability.CONTENT_VIEW)) metadata.replaceCapabilityGrants(id,
                List.of(new CapabilityGrant(id, Capability.CONTENT_VIEW, now, null)), now);
        return new SessionCreateResponse(id,
                properties.publicUrl() + "/editor/session/" + id,
                code,
                properties.publicWebSocketUrl() + "/ws/v1/plugin?session=" + id,
                pluginLease,
                expiresAt);
    }

    public InstallationChallengeResponse challenge(InstallationChallengeRequest request){if(request==null||request.protocolVersion()!=Protocol.VERSION||request.installationId()==null)throw bad("Invalid installation challenge request");PublicKey key=publicKey(request.installationPublicKey());Optional<byte[]> pinned=metadata.installationKey(request.installationId());if(pinned.isPresent()&&!MessageDigest.isEqual(pinned.get(),key.getEncoded()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Installation identity changed");limits.check("installation-challenge",request.installationId().toString(),quotas.sessionCreatesPerInstallation(),quotas.window());UUID id=UUID.randomUUID();String challenge=randomToken();Instant expires=Instant.now().plus(CHALLENGE_TTL);state.put(challengeKey(id),request.installationId()+"\n"+request.installationPublicKey()+"\n"+challenge,CHALLENGE_TTL);return new InstallationChallengeResponse(id,challenge,expires);}

    public InstallationChallengeProofResponse prove(InstallationChallengeProof proof){if(proof==null||proof.protocolVersion()!=Protocol.VERSION||proof.challengeId()==null||proof.installationId()==null)throw bad("Invalid installation challenge proof");String expected=proof.installationId()+"\n"+proof.installationPublicKey()+"\n"+proof.challenge();if(!state.consumeIfEquals(challengeKey(proof.challengeId()),expected))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Installation challenge expired or was already used");if(!verify(publicKey(proof.installationPublicKey()),proof.signingInput(),proof.signature()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid installation challenge signature");String lease=randomToken();Instant expires=Instant.now().plus(INSTALLATION_LEASE_TTL);state.put(installationLeaseKey(proof.installationId()),hash(lease),INSTALLATION_LEASE_TTL);return new InstallationChallengeProofResponse(lease,expires);}

    public SessionCreateResponse create(SessionCreateRequest request,String installationLease){if(request==null||installationLease==null||!state.consumeIfEquals(installationLeaseKey(request.installationId()),hash(installationLease)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Missing, invalid, or reused installation challenge lease");return create(request);}

    public SessionVerifyResponse verify(UUID sessionId, SessionVerifyRequest request) {
        EditorSession session = require(sessionId);
        limits.check("session-verify", sessionId.toString(), quotas.verificationAttemptsPerSession(), quotas.window());
        synchronized (session) {
            if (session.verified()) throw bad("Verification code has already been used");
            if (request == null || request.verificationCode() == null) throw bad("Missing verification code");
            PublicKey browserKey = publicKey(request.browserPublicKey());
            if (!state.consumeIfEquals(verificationKey(sessionId), hash(request.verificationCode()))) {
                if (state.increment("verification-attempts:" + sessionId, properties.sessionLifetime())
                        >= properties.maximumVerificationAttempts()) {
                    sessions.remove(sessionId); revokeState(sessionId); metadata.revokeSession(sessionId, Instant.now());
                }
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect verification code");
            }
            String browserLease = randomToken();
            session.verify(request.browserPublicKey(), browserDescription(request.browserDescription()), hash(browserLease));
            state.put(browserLeaseKey(sessionId), hash(browserLease), stateTtl(session.expiresAt()));
            metadata.bindBrowser(new BrowserIdentity(sessionId, browserKey.getEncoded(),
                    session.browserDescription(), Instant.now()));
            return new SessionVerifyResponse(session.id(),
                    properties.publicWebSocketUrl() + "/ws/v1/browser?session=" + session.id(),
                    browserLease, session.capabilities(), session.expiresAt());
        }
    }

    public EditorSession authenticatePlugin(UUID id, String lease) {
        EditorSession session = require(id);
        if (!constantStateEquals(pluginLeaseKey(id), lease))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid plugin lease");
        session.touchPlugin();
        return session;
    }

    public EditorSession authenticateBrowser(UUID id, String lease) {
        EditorSession session = require(id);
        if (!session.verified() || !constantStateEquals(browserLeaseKey(id), lease))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid browser lease");
        session.touchBrowser();
        return session;
    }

    public EditorSession authenticateEither(UUID id, String lease) {
        EditorSession session = require(id);
        if (!constantStateEquals(pluginLeaseKey(id), lease)
                && (!session.verified() || !constantStateEquals(browserLeaseKey(id), lease)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session lease");
        return session;
    }

    public EditorSessionStatus statusForPlugin(UUID id, String lease) {
        return status(authenticatePlugin(id, lease));
    }

    public EditorSessionStatus statusForBrowser(UUID id, String lease) {
        return status(authenticateBrowser(id, lease));
    }

    public EditorSessionStatus grant(UUID id, String lease, CapabilityGrantRequest request) {
        EditorSession session = authenticatePlugin(id, lease);
        if (request == null || request.protocolVersion() != Protocol.VERSION)
            throw bad("Invalid capability grant envelope");
        if (!session.verified()) throw bad("The browser must be verified before capabilities can be trusted");
        if (!session.requestedCapabilities().containsAll(request.capabilities()))
            throw bad("Cannot grant a capability that this session did not request");
        session.grant(request.capabilities());
        Instant now = Instant.now();
        metadata.replaceCapabilityGrants(id, session.capabilities().stream()
                .map(capability -> new CapabilityGrant(id, capability, now, null)).toList(), now);
        audit.record(session, AuditEvent.ActorType.OPERATOR, session.initiatorId(), AuditEvent.EventType.TRUST,
                AuditEvent.Outcome.SUCCESS, Map.of("capabilities", session.capabilities().stream()
                        .map(Enum::name).sorted().toList()), id.toString());
        return status(session);
    }

    public EditorSessionStatus revokeCapabilities(UUID id, String lease) {
        EditorSession session = authenticatePlugin(id, lease);
        session.revokeElevatedCapabilities();
        Instant now = Instant.now();
        metadata.replaceCapabilityGrants(id, session.capabilities().stream()
                .map(capability -> new CapabilityGrant(id, capability, now, null)).toList(), now);
        audit.record(session, AuditEvent.ActorType.OPERATOR, session.initiatorId(), AuditEvent.EventType.TRUST,
                AuditEvent.Outcome.SUCCESS, Map.of("action", "revoke-elevated"), id.toString());
        return status(session);
    }

    public void revoke(UUID id, String lease) {
        EditorSession session = authenticateEither(id, lease);
        sessions.remove(id);
        revokeState(id);
        metadata.revokeSession(id, Instant.now());
        audit.record(session, AuditEvent.ActorType.OPERATOR, session.initiatorId(),
                AuditEvent.EventType.SESSION_REVOCATION, AuditEvent.Outcome.SUCCESS, Map.of(), id.toString());
    }

    public Set<UUID> removeExpired() {
        Instant now = Instant.now();
        Set<UUID> removed = new HashSet<>();
        sessions.forEach((id, session) -> {
            if (now.isAfter(session.expiresAt()) && sessions.remove(id, session)) removed.add(id);
        });
        return removed;
    }

    public Collection<EditorSession> activeSessions() {
        removeExpired();
        return List.copyOf(sessions.values());
    }

    public EditorSession require(UUID id) {
        EditorSession session = sessions.get(id);
        if (session == null) session = restore(id).orElse(null);
        if (session == null || Instant.now().isAfter(session.expiresAt())) {
            sessions.remove(id);
            throw new ResponseStatusException(HttpStatus.GONE, "Editor session expired or does not exist");
        }
        return session;
    }

    private Optional<EditorSession> restore(UUID id) {
        HostedEditorSession stored = metadata.session(id).orElse(null);
        if (stored == null || stored.revokedAt() != null || !Instant.now().isBefore(stored.expiresAt())) return Optional.empty();
        byte[] installationBytes = metadata.installationKey(stored.installationId()).orElse(null);
        String pluginHash = state.get(pluginLeaseKey(id)).orElse(null);
        if (installationBytes == null || pluginHash == null) return Optional.empty();
        try {
            PublicKey installationKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(installationBytes));
            EditorSession restored = new EditorSession(stored.id(), stored.installationId(), installationKey,
                    stored.initiatorId(), stored.initiatorName(), stored.scope(), stored.expiresAt(),
                    stored.restrictions(), stored.requestedCapabilities(),
                    state.get(verificationKey(id)).orElse(""), pluginHash);
            BrowserIdentity browser = metadata.browserIdentity(id).orElse(null);
            String browserHash = state.get(browserLeaseKey(id)).orElse(null);
            if (browser != null && browserHash != null) restored.verify(
                    Base64.getEncoder().encodeToString(browser.publicKey()), browser.description(), browserHash);
            Set<Capability> capabilities = metadata.activeCapabilityGrants(id);
            if (!capabilities.isEmpty()) restored.grant(capabilities);
            EditorSession raced = sessions.putIfAbsent(id, restored);
            return Optional.of(raced == null ? restored : raced);
        } catch (GeneralSecurityException | IllegalArgumentException corrupted) {
            return Optional.empty();
        }
    }

    private void cleanup() {
        removeExpired();
    }
    private static String browserDescription(String raw) {
        if (raw == null || raw.isBlank()) return "Unknown browser";
        String clean = raw.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        return clean.substring(0, Math.min(clean.length(), 160));
    }
    private static EditorSessionStatus status(EditorSession session) {
        return new EditorSessionStatus(session.id(), session.initiatorId(), session.initiatorName(), session.scope(),
                session.restrictions(),
                session.requestedCapabilities(), session.capabilities(), session.verified(),
                session.browserDescription(), session.expiresAt());
    }
    private boolean constantHashEquals(String raw, String expected) {
        return raw != null && MessageDigest.isEqual(hash(raw).getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
    private boolean constantStateEquals(String key, String raw) {
        return raw != null && state.get(key).map(expected -> constantHashEquals(raw, expected)).orElse(false);
    }
    private void revokeState(UUID id) {
        state.delete(verificationKey(id)); state.delete(pluginLeaseKey(id)); state.delete(browserLeaseKey(id));
        state.delete("verification-attempts:" + id);
    }
    private static String verificationKey(UUID id) { return "session:" + id + ":verification"; }
    private static String pluginLeaseKey(UUID id) { return "session:" + id + ":plugin-lease"; }
    private static String browserLeaseKey(UUID id) { return "session:" + id + ":browser-lease"; }
    private static String challengeKey(UUID id){return "installation-challenge:"+id;}
    private static String installationLeaseKey(UUID id){return "installation-lease:"+id;}
    private static Duration stateTtl(Instant expiresAt) {
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        return remaining.isNegative() || remaining.isZero() ? Duration.ofMillis(1) : remaining;
    }
    private String randomCode(int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) value.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        return value.toString();
    }
    private String randomToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String hash(String value) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    private PublicKey publicKey(String encoded) {
        try { return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded))); }
        catch (GeneralSecurityException | IllegalArgumentException e) { throw bad("Invalid Ed25519 public key"); }
    }
    private boolean verify(PublicKey key, String input, String encodedSignature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key); verifier.update(input.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(encodedSignature));
        } catch (GeneralSecurityException | IllegalArgumentException e) { return false; }
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
