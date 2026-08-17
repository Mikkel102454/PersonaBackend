package nu.miguel.persona.editor.protocol;

import java.util.UUID;

public record InstallationChallengeRequest(int protocolVersion,UUID installationId,String installationPublicKey) {}
