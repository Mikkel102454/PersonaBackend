package nu.miguel.personabackend.administration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("persona.editor.administration")
public record AdministrationProperties(String actuatorToken) {
    public AdministrationProperties {
        actuatorToken = actuatorToken == null ? "" : actuatorToken.trim();
        if (!actuatorToken.isEmpty() && actuatorToken.length() < 32)
            throw new IllegalArgumentException("The actuator token must contain at least 32 characters");
    }
    public boolean enabled() { return !actuatorToken.isEmpty(); }
}
