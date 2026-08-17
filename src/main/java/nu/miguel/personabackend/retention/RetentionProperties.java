package nu.miguel.personabackend.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("persona.editor.retention")
public record RetentionProperties(Duration revisions, Duration drafts, Duration publishes, Duration audit,
                                  Duration sweepInterval, int maximumRevisionsPerInstallation) {
    public RetentionProperties {
        revisions = positive(revisions, Duration.ofDays(30)); drafts = positive(drafts, Duration.ofDays(30));
        publishes = positive(publishes, Duration.ofDays(180)); audit = positive(audit, Duration.ofDays(365));
        sweepInterval = positive(sweepInterval, Duration.ofHours(1));
        if (maximumRevisionsPerInstallation < 1) maximumRevisionsPerInstallation = 100;
    }
    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
