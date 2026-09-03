package vn.com.poc.disruptor.outbox;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryBackoffPolicyTest {

    @Test
    void isExhaustedRespectsMaxAttempts() {
        RetryBackoffPolicy policy = new RetryBackoffPolicy(Duration.ofMillis(100), Duration.ofSeconds(5), 3);
        assertFalse(policy.isExhausted(1));
        assertFalse(policy.isExhausted(2));
        assertTrue(policy.isExhausted(3));
        assertTrue(policy.isExhausted(4));
    }

    @RepeatedTest(50)
    void nextDelayIsWithinFullJitterBounds() {
        Duration base = Duration.ofMillis(100);
        Duration cap = Duration.ofSeconds(2);
        RetryBackoffPolicy policy = new RetryBackoffPolicy(base, cap, 5);

        for (int attempt = 1; attempt <= 8; attempt++) {
            Duration delay = policy.nextDelay(attempt);
            long expectedCeiling = Math.min(cap.toMillis(), base.toMillis() * (1L << attempt));
            assertTrue(delay.toMillis() >= 0, "delay must not be negative");
            assertTrue(delay.toMillis() <= expectedCeiling,
                    "delay " + delay.toMillis() + "ms exceeded full-jitter ceiling " + expectedCeiling + "ms");
        }
    }

    @Test
    void delayNeverExceedsCapEvenAtHighAttemptCounts() {
        RetryBackoffPolicy policy = new RetryBackoffPolicy(Duration.ofMillis(100), Duration.ofSeconds(1), 100);
        for (int attempt = 1; attempt <= 40; attempt++) {
            Duration delay = policy.nextDelay(attempt);
            assertTrue(delay.toMillis() <= 1000, "attempt=" + attempt + " delay=" + delay);
        }
    }

    @Test
    void defaultPolicyShape() {
        RetryBackoffPolicy policy = RetryBackoffPolicy.defaultPolicy();
        assertEquals(5, policy.maxAttempts());
    }
}
