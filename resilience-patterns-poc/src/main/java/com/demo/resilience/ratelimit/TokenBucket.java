package com.demo.resilience.ratelimit;

/**
 * Classic token bucket.
 *
 *  - bucket holds up to `capacity` tokens
 *  - tokens refill at `refillPerSecond` per second, lazily on each tryAcquire
 *  - a request that finds at least 1 token consumes it and is admitted
 *
 * Burstiness is controlled by `capacity`. Long-run throughput is bounded
 * by `refillPerSecond`. Threadsafe via a single synchronized block —
 * the critical section is tiny so contention is negligible at demo scale.
 */
public final class TokenBucket {

    private final double capacity;
    private final double refillPerSecond;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double capacity, double refillPerSecond) {
        if (capacity <= 0 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("capacity and refillPerSecond must be positive");
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    public synchronized double availableTokens() {
        refill();
        return tokens;
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds <= 0) return;
        tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
        lastRefillNanos = now;
    }
}
