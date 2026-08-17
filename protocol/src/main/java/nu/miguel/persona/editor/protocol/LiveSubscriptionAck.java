package nu.miguel.persona.editor.protocol;

import java.util.UUID;

public record LiveSubscriptionAck(int protocolVersion,UUID subscriptionId,boolean accepted,
                                  int refreshMillis,String message) {}
