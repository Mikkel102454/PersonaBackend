package nu.miguel.personabackend.retention;

import nu.miguel.personabackend.storage.*;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public final class RetentionService {
    private static final Logger LOG = LoggerFactory.getLogger(RetentionService.class);
    private final HostedMetadataStore metadata; private final RetentionProperties properties;
    public RetentionService(HostedMetadataStore metadata, RetentionProperties properties) {
        this.metadata = metadata; this.properties = properties;
    }
    @Scheduled(fixedDelayString = "${persona.editor.retention.sweep-interval:1h}",
            initialDelayString = "${persona.editor.retention.sweep-interval:1h}")
    public void sweep() { sweep(Instant.now()); }
    public RetentionResult sweep(Instant now) {
        RetentionResult result = metadata.purge(new RetentionPolicy(now.minus(properties.revisions()),
                now.minus(properties.drafts()), now.minus(properties.publishes()), now.minus(properties.audit()), now,
                properties.maximumRevisionsPerInstallation()));
        if (result.total() > 0) LOG.info("Hosted retention removed revisions={}, drafts={}, publishes={}, audit={}, subscriptions={}, live-traces={}",
                result.revisions(), result.drafts(), result.publishes(), result.auditEvents(), result.subscriptions(), result.liveTraces());
        return result;
    }
}
