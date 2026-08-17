package nu.miguel.personabackend.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.persona.editor.protocol.*;
import nu.miguel.personabackend.audit.AuditService;
import nu.miguel.personabackend.security.*;
import nu.miguel.personabackend.session.*;
import nu.miguel.personabackend.storage.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EditorMetadataServiceTest {
    private final InMemoryHostedMetadataStore metadata=new InMemoryHostedMetadataStore();
    private final InMemoryExpiringStateStore state=new InMemoryExpiringStateStore();
    private final ObjectMapper json=new ObjectMapper();
    private final SessionService sessions=new SessionService(new EditorProperties("https://editor.example","wss://editor.example",Duration.ofMinutes(5),Duration.ofMinutes(1),3,Duration.ofSeconds(45),16),
            new RateLimitService(state),QuotaProperties.defaults(),metadata,state,null);
    private final EditorMetadataService service=new EditorMetadataService(sessions,state,json,new RateLimitService(state),QuotaProperties.defaults(),new AuditService(metadata,json));

    @Test void storesSignedImmutableMetadataAcrossBackendInstances() throws Exception {
        KeyPair installation=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();SessionCreateResponse created=sessions.create(request(installation));
        EditorMetadataSnapshot snapshot=snapshot(installation,created.sessionId(),"{\"type\":\"object\"}");
        assertEquals(snapshot,service.store(created.sessionId(),created.pluginLeaseToken(),snapshot));
        KeyPair browser=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();SessionVerifyResponse verified=sessions.verify(created.sessionId(),new SessionVerifyRequest(created.verificationCode(),Base64.getEncoder().encodeToString(browser.getPublic().getEncoded()),"Browser"));
        EditorMetadataService other=new EditorMetadataService(sessions,state,json,new RateLimitService(state),QuotaProperties.defaults(),new AuditService(metadata,json));
        assertEquals(snapshot,other.read(created.sessionId(),verified.browserLeaseToken()));
        assertTrue(metadata.auditEvents().stream().anyMatch(event->"metadata-download".equals(event.details().get("operation"))));
    }

    @Test void rejectsTamperedSchemaRevisionAndSignature() throws Exception {
        KeyPair installation=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();SessionCreateResponse created=sessions.create(request(installation));
        EditorMetadataSnapshot valid=snapshot(installation,created.sessionId(),"{\"type\":\"string\"}");
        EditorSchemaDocument document=valid.schemas().getFirst();
        EditorSchemaDocument changed=new EditorSchemaDocument(document.contentType(),document.typeId(),document.extensionId(),document.extensionVersion(),"{\"type\":\"number\"}",document.schemaSha256());
        EditorMetadataSnapshot tampered=new EditorMetadataSnapshot(valid.protocolVersion(),valid.sessionId(),valid.createdAt(),valid.installationPublicKey(),valid.revision(),List.of(changed),valid.catalogs(),valid.signature());
        assertThrows(ResponseStatusException.class,()->service.store(created.sessionId(),created.pluginLeaseToken(),tampered));
    }

    private SessionCreateRequest request(KeyPair keys)throws Exception {SessionCreateRequest unsigned=new SessionCreateRequest(Protocol.VERSION,UUID.randomUUID(),Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),"console","CONSOLE",EditorScope.ALL,SessionRestrictions.UNRESTRICTED,Set.of(Capability.CONTENT_VIEW),System.currentTimeMillis(),UUID.randomUUID().toString(),"");
        return new SessionCreateRequest(unsigned.protocolVersion(),unsigned.installationId(),unsigned.installationPublicKey(),unsigned.initiatorId(),unsigned.initiatorName(),unsigned.scope(),unsigned.restrictions(),unsigned.requestedCapabilities(),unsigned.issuedAt(),unsigned.nonce(),sign(keys.getPrivate(),unsigned.signingInput()));}
    private EditorMetadataSnapshot snapshot(KeyPair keys,UUID sessionId,String schema)throws Exception {String digest=sha(schema);EditorSchemaDocument document=new EditorSchemaDocument("command","weather:announce","weather","1.4",schema,digest);
        EditorCatalogDocument catalog=new EditorCatalogDocument("weather:channels","weather","1.4","catalog-2","{\"type\":\"string\"}",sha("{\"type\":\"string\"}"),"persona.weather.view","REVISION",List.of("world"),"WARN");
        EditorMetadataSnapshot draft=new EditorMetadataSnapshot(Protocol.VERSION,sessionId,Instant.now(),Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),"",List.of(document),List.of(catalog),"");String revision=EditorMetadataService.revision(draft.manifest());
        EditorMetadataSnapshot unsigned=new EditorMetadataSnapshot(draft.protocolVersion(),draft.sessionId(),draft.createdAt(),draft.installationPublicKey(),revision,draft.schemas(),draft.catalogs(),"");
        return new EditorMetadataSnapshot(unsigned.protocolVersion(),unsigned.sessionId(),unsigned.createdAt(),unsigned.installationPublicKey(),unsigned.revision(),unsigned.schemas(),unsigned.catalogs(),sign(keys.getPrivate(),unsigned.signingInput()));}
    private static String sign(PrivateKey key,String input)throws Exception {Signature value=Signature.getInstance("Ed25519");value.initSign(key);value.update(input.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(value.sign());}
    private static String sha(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
}
