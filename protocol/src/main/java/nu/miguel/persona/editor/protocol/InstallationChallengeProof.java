package nu.miguel.persona.editor.protocol;

import java.util.UUID;

public record InstallationChallengeProof(int protocolVersion,UUID challengeId,UUID installationId,
                                         String installationPublicKey,String challenge,String signature) {
    public String signingInput(){return protocolVersion+"\n"+challengeId+"\n"+installationId+"\n"+installationPublicKey+"\n"+challenge;}
}
