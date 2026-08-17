package nu.miguel.personabackend.relay;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RelayCircuitBreakerTest {
    @Test void opensAfterBoundedFailuresAndRecoversAfterTimeout(){AtomicLong now=new AtomicLong();AtomicInteger calls=new AtomicInteger();RelayCircuitBreaker breaker=new RelayCircuitBreaker(2,Duration.ofSeconds(5),now::get);Runnable failed=()->{calls.incrementAndGet();throw new IllegalStateException("down");};assertThrows(IllegalStateException.class,()->breaker.execute(failed));assertThrows(IllegalStateException.class,()->breaker.execute(failed));assertThrows(IllegalStateException.class,()->breaker.execute(calls::incrementAndGet));assertEquals(2,calls.get());now.set(5_000);assertDoesNotThrow(()->breaker.execute(calls::incrementAndGet));assertEquals(3,calls.get());}
}
