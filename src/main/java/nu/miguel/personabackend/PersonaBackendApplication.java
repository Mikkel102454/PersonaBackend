package nu.miguel.personabackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import nu.miguel.personabackend.session.EditorProperties;
import nu.miguel.personabackend.security.QuotaProperties;
import nu.miguel.personabackend.relay.RelayCapacityProperties;
import nu.miguel.personabackend.administration.AdministrationProperties;
import nu.miguel.personabackend.publish.PublishProperties;
import nu.miguel.personabackend.retention.RetentionProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({EditorProperties.class, QuotaProperties.class, RelayCapacityProperties.class,
        AdministrationProperties.class, PublishProperties.class, RetentionProperties.class})
public class PersonaBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(PersonaBackendApplication.class, args);
    }

}
