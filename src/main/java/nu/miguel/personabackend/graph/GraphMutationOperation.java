package nu.miguel.personabackend.graph;

import java.util.List;

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
        String sourceFilePath,
        String operationId,
        String nodeId,
        String edgeId,
        String parentPortId,
        String beforePortId,
        String afterPortId,
        EditorGraphProjection.SourceRange expectedSourceRange,
        List<GraphMutationOperation> children,
        String valueType,
        Boolean required,
        String defaultValue,
        String parameterName,
        String newName) {
    public enum Type {
        CONNECT, DISCONNECT, RECONNECT, INSERT, INSERT_ON_WIRE, DELETE, DUPLICATE, COPY,
        REORDER, WRAP, UNWRAP, EDIT_FIELD, COMPOUND, SET_PIN_DEFAULT,
        CREATE_VALUE_NODE, REMOVE_VALUE_NODE, ADD_SCRIPT_PARAMETER, RENAME_SCRIPT_PARAMETER,
        REORDER_SCRIPT_PARAMETER, DELETE_SCRIPT_PARAMETER, CHANGE_SCRIPT_PARAMETER_TYPE
    }
    public GraphMutationOperation {
        children = children == null ? List.of() : List.copyOf(children);
    }
    public GraphMutationOperation(Type type, String yamlPath, String targetYamlPath,
                                  String parentYamlPath, String sourcePinId, String targetPinId,
                                  String nodeKind, String key, String value, Integer index,
                                  String sourceFilePath) {
        this(type, yamlPath, targetYamlPath, parentYamlPath, sourcePinId, targetPinId,
                nodeKind, key, value, index, sourceFilePath, null, null, null, null,
                null, null, null, List.of(),null,null,null,null,null);
    }
    public GraphMutationOperation(Type type,String yamlPath,String targetYamlPath,String parentYamlPath,
                                  String sourcePinId,String targetPinId,String nodeKind,String key,String value,
                                  Integer index,String sourceFilePath,String operationId,String nodeId,String edgeId,
                                  String parentPortId,String beforePortId,String afterPortId,
                                  EditorGraphProjection.SourceRange expectedSourceRange,List<GraphMutationOperation> children){
        this(type,yamlPath,targetYamlPath,parentYamlPath,sourcePinId,targetPinId,nodeKind,key,value,index,
                sourceFilePath,operationId,nodeId,edgeId,parentPortId,beforePortId,afterPortId,expectedSourceRange,
                children,null,null,null,null,null);
    }
}
