package nu.miguel.persona.editor.protocol;

import java.util.UUID;

public record PublishApplyResult(int protocolVersion, UUID publishId, boolean success,
                                 String activeRevision, String backupId, String error) {}
