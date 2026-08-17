package nu.miguel.persona.editor.protocol;

public record PublishConfirmRequest(int protocolVersion, String confirmationCode) {}
