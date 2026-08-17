package nu.miguel.persona.editor.protocol;

public record ValidationDiagnostic(String path, int line, int column, String nodeId,
                                   String referenceType, String referenceId,
                                   String message, String suggestion, String severity) {}
