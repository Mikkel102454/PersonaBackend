package nu.miguel.personabackend.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;

@Configuration
public class TelemetryPrivacyConfiguration {
    private static final Set<String> PRIVATE_KEY_FRAGMENTS = Set.of(
            "memory", "value", "player", "uuid", "initiator", "browser", "chat", "inventory",
            "ip", "address", "secret", "token", "lease", "code", "yaml", "content");

    @Bean ObservationFilter removePrivateTraceAttributes() {
        return context -> {
            List<String> low = new ArrayList<>(), high = new ArrayList<>();
            context.getLowCardinalityKeyValues().forEach(value -> { if (privateKey(value.getKey())) low.add(value.getKey()); });
            context.getHighCardinalityKeyValues().forEach(value -> { if (privateKey(value.getKey())) high.add(value.getKey()); });
            context.removeLowCardinalityKeyValues(low.toArray(String[]::new));
            context.removeHighCardinalityKeyValues(high.toArray(String[]::new));
            return context;
        };
    }

    @Bean MeterFilter removePrivateMetricTags() {
        return new MeterFilter() {
            @Override public Meter.Id map(Meter.Id id) {
                return id.replaceTags(id.getTags().stream().filter(tag -> !privateKey(tag.getKey())).toList());
            }
        };
    }

    @Bean MeterFilter boundMetricCardinality() { return MeterFilter.maximumAllowableMetrics(10_000); }

    static boolean privateKey(String key) {
        String normalized = Objects.toString(key, "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return PRIVATE_KEY_FRAGMENTS.stream().anyMatch(normalized::contains);
    }
}
