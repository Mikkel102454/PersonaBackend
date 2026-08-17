package nu.miguel.personabackend.reference;

public record ProjectReference(String sourceType, String sourceId, String targetType, String targetId,
                               String path, String yamlPath, int line, int column, boolean resolved) {}
