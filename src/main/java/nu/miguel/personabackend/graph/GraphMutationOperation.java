package nu.miguel.personabackend.graph;

/** One bounded, typed graph edit. Fields not used by an operation must be null. */
public record GraphMutationOperation(
        Type type,
        String yamlPath,
        String targetYamlPath,
        String parentYamlPath,
        String sourcePinId,
        String targetPinId,
        String nodeKind,
        String key,
        String value,
        Integer index,
        String sourceFilePath) {
    public enum Type {
        CONNECT, DISCONNECT, INSERT, DELETE, DUPLICATE, COPY, REORDER, WRAP, UNWRAP, EDIT_FIELD
    }
}
