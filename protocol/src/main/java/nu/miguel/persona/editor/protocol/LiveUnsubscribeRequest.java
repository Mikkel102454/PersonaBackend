package nu.miguel.persona.editor.protocol;

import java.util.UUID;

public record LiveUnsubscribeRequest(int protocolVersion,UUID subscriptionId) {}
