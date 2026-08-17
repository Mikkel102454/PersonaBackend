package nu.miguel.persona.editor.protocol;

import java.util.UUID;

/** Relay-generated transport state. It never carries authoritative Persona data. */
public record RelayControlMessage(
        int protocolVersion,
        UUID sessionId,
        String controlType,
        long latestSequence
) {}
