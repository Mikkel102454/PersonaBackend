package nu.miguel.personabackend.storage;

public record RetentionResult(int revisions, int drafts, int publishes, int auditEvents,
                              int subscriptions, int liveTraces) {
    public int total() { return revisions + drafts + publishes + auditEvents + subscriptions + liveTraces; }
}
