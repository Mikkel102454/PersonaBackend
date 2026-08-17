package nu.miguel.persona.editor.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = false)
public record SocketMessage(
        int protocolVersion,
        UUID sessionId,
        long sequence,
        String type,
        Map<String, Object> payload,
        String signature
) {}
