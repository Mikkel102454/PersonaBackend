package nu.miguel.personabackend.document;

public record YamlDiagnostic(int line, int column, String message) {}
