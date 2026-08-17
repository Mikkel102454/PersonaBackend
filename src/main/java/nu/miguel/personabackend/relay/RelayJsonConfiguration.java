package nu.miguel.personabackend.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The signed protocol deliberately stays on Jackson 2 for plugin compatibility. */
@Configuration
public class RelayJsonConfiguration {
    @Bean
    public ObjectMapper protocolObjectMapper() { return new ObjectMapper(); }
}
