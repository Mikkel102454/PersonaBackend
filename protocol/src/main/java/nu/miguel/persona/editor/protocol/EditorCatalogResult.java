package nu.miguel.persona.editor.protocol;

import java.util.List;
import java.util.UUID;

public record EditorCatalogResult(int protocolVersion,UUID requestId,String catalogId,String revision,Status status,
                                  List<Value> values,int page,boolean hasMore,String message) {
    public EditorCatalogResult { values=values==null?List.of():List.copyOf(values);message=message==null?"":message; }
    public enum Status { LIVE, STALE, UNAVAILABLE, DENIED, ERROR }
    public record Value(String id,String label,String description,String group,String icon,boolean deprecated) {}
}
