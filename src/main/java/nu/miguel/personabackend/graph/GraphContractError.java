package nu.miguel.personabackend.graph;

public record GraphContractError(String code, String message, String filePath, String yamlPath) {}
