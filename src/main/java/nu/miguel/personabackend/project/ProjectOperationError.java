package nu.miguel.personabackend.project;

public record ProjectOperationError(String code, String message, String filePath, String yamlPath) {}
