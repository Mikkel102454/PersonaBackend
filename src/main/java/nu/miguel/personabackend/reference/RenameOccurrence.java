package nu.miguel.personabackend.reference;

public record RenameOccurrence(String path, String yamlPath, int line, int column, String role) {}
