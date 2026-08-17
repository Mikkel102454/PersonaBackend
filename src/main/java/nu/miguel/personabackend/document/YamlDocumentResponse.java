package nu.miguel.personabackend.document;

import java.util.List;

public record YamlDocumentResponse(boolean valid, String content, YamlDocumentNode root,
                                   List<YamlDiagnostic> diagnostics) {
    public YamlDocumentResponse { diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics); }
}
