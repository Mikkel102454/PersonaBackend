package nu.miguel.persona.editor.protocol;

public record EditorSchemaDocument(String contentType,String typeId,String extensionId,
                                   String extensionVersion,String schemaJson,String schemaSha256) {
    public String manifestLine(){return contentType+"\0"+typeId+"\0"+extensionId+"\0"+extensionVersion+"\0"+schemaSha256;}
}
