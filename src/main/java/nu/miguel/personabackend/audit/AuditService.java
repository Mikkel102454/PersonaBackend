package nu.miguel.personabackend.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.personabackend.domain.AuditEvent;
import nu.miguel.personabackend.session.EditorSession;
import nu.miguel.personabackend.storage.HostedMetadataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public final class AuditService {
    private static final Logger LOG = LoggerFactory.getLogger("persona.editor.audit");
    private static final Set<String> FORBIDDEN_DETAIL_KEYS = Set.of(
            "memory", "value", "oldvalue", "newvalue", "chat", "inventory", "ip", "address", "lease", "code", "secret");
    private final HostedMetadataStore store;
    private final ObjectMapper json;

    public AuditService(HostedMetadataStore store, ObjectMapper json) { this.store = store; this.json = json; }

    public AuditEvent record(EditorSession session, AuditEvent.ActorType actorType, String actorId,
                             AuditEvent.EventType type, AuditEvent.Outcome outcome,
                             Map<String, ?> details, String correlationId) {
        AuditEvent event = new AuditEvent(UUID.randomUUID(), session == null ? null : session.installationId(),
                session == null ? null : session.id(), actorType, bounded(actorId, 160), type, outcome,
                Instant.now(), sanitize(details), bounded(correlationId, 128));
        store.appendAudit(event);
        try { LOG.info("{}", json.writeValueAsString(event)); }
        catch (Exception error) { LOG.warn("Could not serialize audit event {}", event.id()); }
        return event;
    }

    private static Map<String, Object> sanitize(Map<String, ?> source) {
        if (source == null || source.isEmpty()) return Map.of();
        if (source.size() > 32) throw new IllegalArgumentException("Audit detail map exceeds 32 entries");
        Map<String, Object> result = new TreeMap<>();
        source.forEach((rawKey, rawValue) -> {
            String key = bounded(Objects.toString(rawKey, ""), 64).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
            String collapsed = key.replaceAll("[^a-z0-9]", "");
            if (FORBIDDEN_DETAIL_KEYS.stream().anyMatch(collapsed::contains)) return;
            Object value = rawValue instanceof Number || rawValue instanceof Boolean ? rawValue
                    : bounded(Objects.toString(rawValue, ""), 256);
            result.put(key, value);
        });
        return Map.copyOf(result);
    }
    private static String bounded(String value, int maximum) {
        if (value == null) return null;
        String clean = value.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        return clean.substring(0, Math.min(clean.length(), maximum));
    }
}
