package nu.miguel.personabackend.relay;

import java.time.Duration;
import java.util.function.LongSupplier;

/** Small fail-fast breaker that prevents a failed coordination backend from stalling socket threads. */
final class RelayCircuitBreaker {
    private final int threshold;private final long openMillis;private final LongSupplier clock;private int failures;private long openUntil;
    RelayCircuitBreaker(int threshold,Duration openFor){this(threshold,openFor,System::currentTimeMillis);}
    RelayCircuitBreaker(int threshold,Duration openFor,LongSupplier clock){if(threshold<1||openFor==null||openFor.isNegative()||openFor.isZero())throw new IllegalArgumentException("Invalid circuit breaker settings");this.threshold=threshold;this.openMillis=openFor.toMillis();this.clock=clock;}
    synchronized void execute(Runnable operation){long now=clock.getAsLong();if(now<openUntil)throw new IllegalStateException("Relay coordination circuit is open");try{operation.run();failures=0;openUntil=0;}catch(RuntimeException error){if(++failures>=threshold){openUntil=now+openMillis;failures=0;}throw error;}}
}
