package nu.miguel.personabackend.relay;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("persona.editor.relay")
public record RelayCapacityProperties(int sendTimeLimitMillis, int sendBufferBytes, Duration presenceTtl) {
    public RelayCapacityProperties {
        if (sendTimeLimitMillis < 100) sendTimeLimitMillis = 5_000;
        if (sendBufferBytes < 65_536) sendBufferBytes = 2 * 1_024 * 1_024;
        if (presenceTtl == null || presenceTtl.isNegative() || presenceTtl.isZero()) presenceTtl = Duration.ofSeconds(60);
    }
    public static RelayCapacityProperties defaults() { return new RelayCapacityProperties(5_000, 2 * 1_024 * 1_024, Duration.ofSeconds(60)); }
}
