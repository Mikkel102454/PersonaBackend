package nu.miguel.personabackend.session;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.net.URI;
import java.util.Set;

@ConfigurationProperties("persona.editor")
public record EditorProperties(
        String publicUrl,
        String publicWebSocketUrl,
        Duration sessionLifetime,
        Duration requestClockSkew,
        int maximumVerificationAttempts,
        Duration socketIdleTimeout,
        int replayCapacity
) {
    public EditorProperties {
        if (publicUrl == null || publicUrl.isBlank()) publicUrl = "http://localhost:8080";
        if (publicWebSocketUrl == null || publicWebSocketUrl.isBlank()) publicWebSocketUrl = "ws://localhost:8080";
        if (sessionLifetime == null) sessionLifetime = Duration.ofHours(8);
        if (requestClockSkew == null) requestClockSkew = Duration.ofMinutes(1);
        if (maximumVerificationAttempts < 1) maximumVerificationAttempts = 5;
        if (socketIdleTimeout == null || socketIdleTimeout.isNegative() || socketIdleTimeout.isZero())
            socketIdleTimeout = Duration.ofSeconds(45);
        if (replayCapacity < 1) replayCapacity = 256;
        requireSecure(publicUrl, "https", "public URL");
        requireSecure(publicWebSocketUrl, "wss", "public WebSocket URL");
    }

    private static void requireSecure(String value, String scheme, String label) {
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid " + label, e); }
        boolean local = Set.of("localhost", "127.0.0.1", "::1").contains(uri.getHost());
        if (!scheme.equalsIgnoreCase(uri.getScheme()) && !local)
            throw new IllegalArgumentException(label + " must use " + scheme + " outside localhost");
    }
}
