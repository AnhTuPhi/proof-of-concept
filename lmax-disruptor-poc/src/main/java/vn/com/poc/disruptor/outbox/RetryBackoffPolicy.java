package vn.com.poc.disruptor.outbox;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "Full jitter" exponential backoff, as described in the AWS Architecture
 * Blog's "Exponential Backoff And Jitter" (Marc Brooker, 2015):
 *
 * <pre>
 *   delay = random_between(0, min(cap, base * 2^attempt))
 * </pre>
 *
 * Plain exponential backoff without jitter synchronizes retries across every
 * failed message from the same batch — they all wake up and hammer the
 * downstream at the same instant, which is exactly the retry storm you were
 * trying to avoid. Full jitter spreads that retry load out.
 */
public final class RetryBackoffPolicy {

    private final Duration base;
    private final Duration cap;
    private final int maxAttempts;

    public RetryBackoffPolicy(Duration base, Duration cap, int maxAttempts) {
        this.base = base;
        this.cap = cap;
        this.maxAttempts = maxAttempts;
    }

    public static RetryBackoffPolicy defaultPolicy() {
        return new RetryBackoffPolicy(Duration.ofMillis(200), Duration.ofSeconds(10), 5);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public boolean isExhausted(int attemptsSoFar) {
        return attemptsSoFar >= maxAttempts;
    }

    /** {@code attempt} is 1 for the first retry after the initial failure. */
    public Duration nextDelay(int attempt) {
        long capMillis = cap.toMillis();
        long exp = base.toMillis() * (1L << Math.min(attempt, 32));
        long boundedMillis = Math.min(capMillis, exp);
        long jittered = ThreadLocalRandom.current().nextLong(boundedMillis + 1);
        return Duration.ofMillis(jittered);
    }
}
