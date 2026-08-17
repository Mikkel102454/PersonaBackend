package nu.miguel.persona.editor.protocol;

import java.util.List;
import java.util.UUID;

/** Persona-signed validation outcome. No candidate content or runtime mutation is carried here. */
public record ValidationResult(int protocolVersion, UUID requestId, UUID draftId, boolean valid,
                               String proposedRevision, int contentFormatVersion, List<ValidationDiagnostic> diagnostics) {
    public ValidationResult { diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics); }
}
