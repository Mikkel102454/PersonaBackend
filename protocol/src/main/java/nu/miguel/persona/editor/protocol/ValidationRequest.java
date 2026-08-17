package nu.miguel.persona.editor.protocol;

import java.util.UUID;

/** Browser-signed request containing identifiers only; project bytes are fetched over the plugin lease. */
public record ValidationRequest(int protocolVersion, UUID requestId, UUID draftId) {}
