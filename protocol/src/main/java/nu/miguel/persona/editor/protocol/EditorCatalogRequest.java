package nu.miguel.persona.editor.protocol;

import java.util.Map;
import java.util.UUID;

public record EditorCatalogRequest(int protocolVersion,UUID requestId,String catalogId,String expectedRevision,
                                   String search,int page,int pageSize,Map<String,String> dependencies) {
    public EditorCatalogRequest { search=search==null?"":search;dependencies=dependencies==null?Map.of():Map.copyOf(dependencies); }
}
