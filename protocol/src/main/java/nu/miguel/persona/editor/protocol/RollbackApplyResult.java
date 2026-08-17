package nu.miguel.persona.editor.protocol;

import java.util.UUID;

public record RollbackApplyResult(int protocolVersion, UUID rollbackId, UUID publishId, boolean success,
                                  String activeRevision, String safetyBackupId, String error) {}
