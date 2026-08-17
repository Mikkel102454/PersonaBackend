package nu.miguel.personabackend.document;

public record YamlMappingInsertRequest(String content,String parentPath,String key,String yamlValue) {}
