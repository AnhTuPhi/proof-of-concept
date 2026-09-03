package com.claude.emqx.common.util;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Jittered exponential backoff - the standard fix for the connection-storm
 * problem demonstrated in POC 02.
 *
 * <p>Strategy used: "decorrelated jitter" (Marc Brooker, AWS Architecture Blog).
 * It outperforms "full jitter" when you have many clients reconnecting because
 * it derives the next sleep from the previous one rather than from a counter,
 * which avoids the synchronized waves that pure exponential backoff produces.
 *
 * <pre>
 *   sleep = min(cap, random_between(base, prev_sleep * 3))
 * </pre>
 *
 * <p>Use it from every reconnect loop, including the broker-discovery path
 * (when the LB rotates to a different node mid-recovery).
 */
public final class JitteredBackoff {

    private final long baseMs;
    private final long capMs;
    private long previousMs;

    public JitteredBackoff(Duration base, Duration cap) {
        this.baseMs = base.toMillis();
        this.capMs = cap.toMillis();
        this.previousMs = baseMs;
    }

    public Duration next() {
        long upper = Math.min(capMs, previousMs * 3);
        long sleep = ThreadLocalRandom.current().nextLong(baseMs, Math.max(baseMs + 1, upper));
        previousMs = sleep;
        return Duration.ofMillis(sleep);
    }

    public void reset() { this.previousMs = baseMs; }
}
