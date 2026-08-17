package nu.miguel.personabackend.publish;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("persona.editor.publish")
public record PublishProperties(Duration confirmationLifetime, int requestsPerSession) {
    public PublishProperties {
        if (confirmationLifetime == null || confirmationLifetime.isNegative() || confirmationLifetime.isZero())
            confirmationLifetime = Duration.ofMinutes(5);
        if (requestsPerSession < 1) requestsPerSession = 20;
    }
}
