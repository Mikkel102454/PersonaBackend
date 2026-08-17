package nu.miguel.personabackend.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("persona.editor.quotas")
public record QuotaProperties(
        int sessionCreatesPerInstallation,
        int verificationAttemptsPerSession,
        int connectionsPerInstallation,
        int messagesPerSession,
        int snapshotsPerSession,
        int draftsPerSession,
        Duration window
) {
    public QuotaProperties {
        if (sessionCreatesPerInstallation < 1) sessionCreatesPerInstallation = 10;
        if (verificationAttemptsPerSession < 1) verificationAttemptsPerSession = 10;
        if (connectionsPerInstallation < 1) connectionsPerInstallation = 30;
        if (messagesPerSession < 1) messagesPerSession = 600;
        if (snapshotsPerSession < 1) snapshotsPerSession = 60;
        if (draftsPerSession < 1) draftsPerSession = 120;
        if (window == null || window.isZero() || window.isNegative()) window = Duration.ofMinutes(1);
    }

    public static QuotaProperties defaults() { return new QuotaProperties(10, 10, 30, 600, 60, 120, Duration.ofMinutes(1)); }
}
