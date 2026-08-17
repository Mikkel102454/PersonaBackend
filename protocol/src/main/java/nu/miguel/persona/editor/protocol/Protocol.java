package nu.miguel.persona.editor.protocol;

public final class Protocol {
    public static final int VERSION = 3;
    public static final int MAX_MESSAGE_BYTES = 1_048_576;
    public static final String HEARTBEAT = "HEARTBEAT";
    public static final String SNAPSHOT_CHANGED = "SNAPSHOT_CHANGED";
    public static final String RESYNC_REQUEST = "RESYNC_REQUEST";
    public static final String REPLAY_COMPLETE = "REPLAY_COMPLETE";
    public static final String RESYNC_REQUIRED = "RESYNC_REQUIRED";
    public static final String VALIDATION_REQUEST = "VALIDATION_REQUEST";
    public static final String VALIDATION_RESULT = "VALIDATION_RESULT";
    public static final String CATALOG_REQUEST = "CATALOG_REQUEST";
    public static final String CATALOG_RESULT = "CATALOG_RESULT";
    public static final String LIVE_SUBSCRIBE = "LIVE_SUBSCRIBE";
    public static final String LIVE_UNSUBSCRIBE = "LIVE_UNSUBSCRIBE";
    public static final String LIVE_SUBSCRIPTION_ACK = "LIVE_SUBSCRIPTION_ACK";
    public static final String LIVE_SNAPSHOT = "LIVE_SNAPSHOT";
    public static final String LIVE_DELTA = "LIVE_DELTA";
    public static final String BEHAVIOR_MUTATION_REQUEST = "BEHAVIOR_MUTATION_REQUEST";
    public static final String MEMORY_MUTATION_REQUEST = "MEMORY_MUTATION_REQUEST";
    public static final String LIVE_MUTATION_RESULT = "LIVE_MUTATION_RESULT";

    private Protocol() {}
}
