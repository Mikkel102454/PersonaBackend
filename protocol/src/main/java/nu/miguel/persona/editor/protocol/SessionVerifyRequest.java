package nu.miguel.persona.editor.protocol;

public record SessionVerifyRequest(String verificationCode, String browserPublicKey, String browserDescription) {}
