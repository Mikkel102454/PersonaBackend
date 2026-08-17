package nu.miguel.persona.editor.protocol;

import java.util.List;

public record EditorCatalogDocument(String catalogId,String extensionId,String extensionVersion,String revision,
                                    String valueSchemaJson,String valueSchemaSha256,String permission,
                                    String cachePolicy,List<String> dependencyFields,String missingValuePolicy) {
    public EditorCatalogDocument { dependencyFields=dependencyFields==null?List.of():List.copyOf(dependencyFields); }
    public String manifestLine(){return catalogId+"\0"+extensionId+"\0"+extensionVersion+"\0"+revision+"\0"+
            valueSchemaSha256+"\0"+permission+"\0"+cachePolicy+"\0"+String.join(",",dependencyFields)+"\0"+missingValuePolicy;}
}
