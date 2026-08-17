package nu.miguel.personabackend.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.*;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryPrivacyConfigurationTest {
    private final TelemetryPrivacyConfiguration privacy = new TelemetryPrivacyConfiguration();

    @Test void stripsPrivateTraceAttributesButKeepsOperationalDimensions() {
        Observation.Context context = new Observation.Context()
                .addLowCardinalityKeyValue(KeyValue.of("operation", "snapshot"))
                .addLowCardinalityKeyValue(KeyValue.of("player.uuid", "private"))
                .addHighCardinalityKeyValue(KeyValue.of("memory.value", "secret"))
                .addHighCardinalityKeyValue(KeyValue.of("http.route", "/api/v1/editor/sessions/{id}"));

        privacy.removePrivateTraceAttributes().map(context);

        assertEquals("snapshot", context.getLowCardinalityKeyValue("operation").getValue());
        assertNull(context.getLowCardinalityKeyValue("player.uuid"));
        assertNull(context.getHighCardinalityKeyValue("memory.value"));
        assertNotNull(context.getHighCardinalityKeyValue("http.route"));
    }

    @Test void stripsPrivateMetricTags() {
        Meter.Id source = new Meter.Id("persona.events", Tags.of("result", "success", "player", "uuid"),
                null, null, Meter.Type.COUNTER);
        Meter.Id filtered = privacy.removePrivateMetricTags().map(source);
        assertEquals("success", filtered.getTag("result"));
        assertNull(filtered.getTag("player"));
    }
}
