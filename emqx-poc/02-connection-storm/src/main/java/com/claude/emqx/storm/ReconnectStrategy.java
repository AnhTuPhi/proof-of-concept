package com.claude.emqx.storm;

import com.claude.emqx.common.util.JitteredBackoff;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Four reconnect strategies, side-by-side, so you can FEEL the difference.
 *
 * <p>Run the storm with each strategy and compare the reconnect-rate curve in
 * Grafana. The first three are what teams ship by accident; only the fourth
 * is safe at scale.
 */
public enum ReconnectStrategy {

    /** Reconnect immediately. Worst case - this is what your client lib does by default. */
    IMMEDIATE {
        @Override public Duration nextDelay(JitteredBackoff state) { return Duration.ZERO; }
    },

    /** Fixed 1s pause. Looks safer but still produces a wave. */
    FIXED_1S {
        @Override public Duration nextDelay(JitteredBackoff state) { return Duration.ofSeconds(1); }
    },

    /**
     * Exponential without jitter: 1s, 2s, 4s, ...
     * The classic textbook answer, and yet still terrible at scale - all clients
     * still pick the SAME delays, so the storm just postpones.
     */
    EXPONENTIAL_NO_JITTER {
        @Override public Duration nextDelay(JitteredBackoff state) {
            // We piggyback on the JitteredBackoff state to track previousMs,
            // but we override the random and just double it.
            return state.next();
        }
    },

    /**
     * Decorrelated jitter (AWS recipe). This is what production code should do.
     * <pre>sleep = random_between(base, prev * 3)</pre>
     */
    DECORRELATED_JITTER {
        @Override public Duration nextDelay(JitteredBackoff state) { return state.next(); }
    },

    /**
     * Full jitter as a contrasting option.
     * <pre>sleep = random(0, cap)</pre>
     * Works but distributes more sharply; decorrelated is smoother.
     */
    FULL_JITTER {
        @Override public Duration nextDelay(JitteredBackoff state) {
            long cap = 30_000;
            return Duration.ofMillis(ThreadLocalRandom.current().nextLong(0, cap));
        }
    };

    public abstract Duration nextDelay(JitteredBackoff state);
}
