package nu.miguel.personabackend.diff;

public record SemanticDiffEntry(String category, String path, String yamlPath, String change,
                                String beforeKind, String beforeValue, String afterKind, String afterValue) {}
