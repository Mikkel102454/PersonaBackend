package nu.miguel.persona.editor.protocol;

import java.util.UUID;

public record RollbackProject(int protocolVersion, UUID rollbackId, UUID publishId, UUID sessionId,
                              EditorScope scope, String currentRevision, String targetRevision, String backupId) {}
