package nu.miguel.persona.editor.protocol;

import java.time.Instant;
import java.util.*;

/** Signed, immutable extension metadata captured by Persona for one trusted session. */
public record EditorMetadataSnapshot(int protocolVersion,UUID sessionId,Instant createdAt,String installationPublicKey,
                                     String revision,List<EditorSchemaDocument> schemas,
                                     List<EditorCatalogDocument> catalogs,String signature) {
    public EditorMetadataSnapshot {
        schemas=schemas==null?List.of():List.copyOf(schemas);catalogs=catalogs==null?List.of():List.copyOf(catalogs);
    }
    public List<String> manifest(){
        List<String> lines=new ArrayList<>();schemas.forEach(value->lines.add("schema\0"+value.manifestLine()));
        catalogs.forEach(value->lines.add("catalog\0"+value.manifestLine()));Collections.sort(lines);return List.copyOf(lines);
    }
    public String signingInput(){StringBuilder value=new StringBuilder().append(protocolVersion).append('\n').append(sessionId)
            .append('\n').append(createdAt).append('\n').append(installationPublicKey).append('\n').append(revision);
        manifest().forEach(line->value.append('\n').append(line));return value.toString();}
}
