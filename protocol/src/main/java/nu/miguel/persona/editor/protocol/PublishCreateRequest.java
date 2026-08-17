package nu.miguel.persona.editor.protocol;

import java.util.UUID;

public record PublishCreateRequest(int protocolVersion, UUID draftId, String proposedRevision) {}
