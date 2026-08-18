package nu.miguel.personabackend.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.persona.editor.protocol.*;
import nu.miguel.personabackend.audit.AuditService;
import nu.miguel.personabackend.domain.AuditEvent;
import nu.miguel.personabackend.security.QuotaProperties;
import nu.miguel.personabackend.security.RateLimitService;
import nu.miguel.personabackend.session.EditorSession;
import nu.miguel.personabackend.session.SessionService;
import nu.miguel.personabackend.storage.ExpiringStateStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public final class EditorMetadataService {
    private static final int MAX_DOCUMENTS=2_048,MAX_BYTES=2*1_024*1_024;
    private final SessionService sessions;private final ExpiringStateStore state;private final ObjectMapper json;
    private final RateLimitService limits;private final QuotaProperties quotas;private final AuditService audit;
    public EditorMetadataService(SessionService sessions,ExpiringStateStore state,ObjectMapper json,
                                 RateLimitService limits,QuotaProperties quotas,AuditService audit){
        this.sessions=sessions;this.state=state;this.json=json;this.limits=limits;this.quotas=quotas;this.audit=audit;
    }
    public EditorMetadataSnapshot store(UUID sessionId,String lease,EditorMetadataSnapshot snapshot){
        EditorSession session=sessions.authenticatePlugin(sessionId,lease);limits.check("metadata-upload",sessionId.toString(),quotas.snapshotsPerSession(),quotas.window());validate(session,snapshot);
        Duration ttl=Duration.between(Instant.now(),session.expiresAt());if(ttl.isNegative()||ttl.isZero())throw bad(HttpStatus.GONE,"Session expired");
        state.put(key(sessionId),write(snapshot),ttl);state.put(revisionKey(sessionId),snapshot.revision(),ttl);audit.record(session,AuditEvent.ActorType.INSTALLATION,session.installationId().toString(),
                AuditEvent.EventType.SNAPSHOT_ACCESS,AuditEvent.Outcome.SUCCESS,Map.of("operation","metadata-upload","revision",snapshot.revision(),"schemas",snapshot.schemas().size(),"catalogs",snapshot.catalogs().size()),sessionId.toString());return snapshot;
    }
    public EditorMetadataSnapshot read(UUID sessionId,String lease){
        EditorSession session=sessions.authenticateBrowser(sessionId,lease);limits.check("metadata-download",sessionId.toString(),quotas.snapshotsPerSession(),quotas.window());
        EditorMetadataSnapshot result=state.get(key(sessionId)).map(this::read).orElseThrow(()->bad(HttpStatus.NOT_FOUND,"Editor metadata is not available"));
        audit.record(session,AuditEvent.ActorType.BROWSER,session.browserDescription(),AuditEvent.EventType.SNAPSHOT_ACCESS,AuditEvent.Outcome.SUCCESS,
                Map.of("operation","metadata-download","revision",result.revision()),sessionId.toString());return result;
    }
    private void validate(EditorSession session,EditorMetadataSnapshot value){
        if(value==null||value.protocolVersion()!=Protocol.VERSION||!session.id().equals(value.sessionId())||value.createdAt()==null
                ||value.installationPublicKey()==null||value.revision()==null||value.signature()==null
                ||value.schemas().size()+value.catalogs().size()>MAX_DOCUMENTS)throw bad(HttpStatus.BAD_REQUEST,"Invalid editor metadata envelope");
        if(!MessageDigest.isEqual(session.installationKey().getEncoded(),decode(value.installationPublicKey())))throw bad(HttpStatus.UNAUTHORIZED,"Metadata installation identity does not match session");
        Set<String> ids=new HashSet<>();int bytes=0;
        for(EditorSchemaDocument document:value.schemas()){
            if(document==null||blank(document.contentType(),document.typeId(),document.extensionId(),document.extensionVersion(),document.schemaJson(),document.schemaSha256())
                    ||!ids.add("schema:"+document.contentType()+":"+document.typeId()))throw bad(HttpStatus.BAD_REQUEST,"Invalid or duplicate editor schema");
            bytes+=validateJson(document.schemaJson(),document.schemaSha256());
        }
        for(EditorCatalogDocument document:value.catalogs()){
            if(document==null||blank(document.catalogId(),document.extensionId(),document.extensionVersion(),document.revision(),document.valueSchemaJson(),document.valueSchemaSha256(),document.cachePolicy(),document.missingValuePolicy())
                    ||!ids.add("catalog:"+document.catalogId())||document.dependencyFields().stream().anyMatch(String::isBlank))throw bad(HttpStatus.BAD_REQUEST,"Invalid or duplicate editor catalog");
            bytes+=validateJson(document.valueSchemaJson(),document.valueSchemaSha256());
        }
        if(bytes>MAX_BYTES)throw bad(HttpStatus.PAYLOAD_TOO_LARGE,"Editor metadata exceeds 2 MiB");
        if(!constantEquals(revision(value.manifest()),value.revision()))throw bad(HttpStatus.BAD_REQUEST,"Editor metadata revision is invalid");
        if(!verify(session.installationKey(),value.signingInput(),value.signature()))throw bad(HttpStatus.UNAUTHORIZED,"Invalid editor metadata signature");
    }
    private int validateJson(String source,String expected){byte[] bytes=source.getBytes(StandardCharsets.UTF_8);try{if(!json.readTree(source).isObject())throw new IllegalArgumentException();}
        catch(Exception error){throw bad(HttpStatus.BAD_REQUEST,"Editor schema must be a JSON object");}if(!constantEquals(hex(digest().digest(bytes)),expected))throw bad(HttpStatus.BAD_REQUEST,"Editor schema digest is invalid");return bytes.length;}
    public static String revision(List<String> manifest){MessageDigest digest=digest();manifest.forEach(line->{digest.update(line.getBytes(StandardCharsets.UTF_8));digest.update((byte)'\n');});return hex(digest.digest());}
    private String write(EditorMetadataSnapshot value){try{return json.writeValueAsString(new StoredMetadata(value.protocolVersion(),value.sessionId(),value.createdAt().toString(),
            value.installationPublicKey(),value.revision(),value.schemas(),value.catalogs(),value.signature()));}catch(JsonProcessingException error){throw new IllegalArgumentException(error);}}
    private EditorMetadataSnapshot read(String value){try{StoredMetadata stored=json.readValue(value,StoredMetadata.class);return new EditorMetadataSnapshot(stored.protocolVersion(),stored.sessionId(),
            Instant.parse(stored.createdAt()),stored.installationPublicKey(),stored.revision(),stored.schemas(),stored.catalogs(),stored.signature());}catch(JsonProcessingException error){throw new IllegalStateException(error);}}
    private record StoredMetadata(int protocolVersion,UUID sessionId,String createdAt,String installationPublicKey,String revision,
                                  List<EditorSchemaDocument> schemas,List<EditorCatalogDocument> catalogs,String signature){}
    private static String key(UUID id){return "editor:metadata:"+id;}
    private static String revisionKey(UUID id){return "editor:metadata-revision:"+id;}
    public Optional<String> currentRevision(UUID sessionId){return state.get(revisionKey(sessionId));}
    /** Internal read for an already authenticated session-scoped controller. */
    public Optional<EditorMetadataSnapshot> current(UUID sessionId){return state.get(key(sessionId)).map(this::read);}
    private static boolean blank(String... values){return Arrays.stream(values).anyMatch(value->value==null||value.isBlank());}
    private static byte[] decode(String value){try{return Base64.getDecoder().decode(value);}catch(IllegalArgumentException e){throw bad(HttpStatus.BAD_REQUEST,"Invalid installation public key");}}
    private static boolean verify(PublicKey key,String input,String encoded){try{Signature signature=Signature.getInstance("Ed25519");signature.initVerify(key);signature.update(input.getBytes(StandardCharsets.UTF_8));return signature.verify(Base64.getDecoder().decode(encoded));}catch(GeneralSecurityException|IllegalArgumentException e){return false;}}
    private static boolean constantEquals(String left,String right){return right!=null&&MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),right.getBytes(StandardCharsets.US_ASCII));}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String hex(byte[] value){return HexFormat.of().formatHex(value);}
    private static ResponseStatusException bad(HttpStatus status,String message){return new ResponseStatusException(status,message);}
}
