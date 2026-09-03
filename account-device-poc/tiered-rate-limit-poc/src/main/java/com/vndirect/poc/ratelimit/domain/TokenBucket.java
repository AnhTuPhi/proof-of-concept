package com.vndirect.poc.ratelimit.domain;

/**
 * Lazy token bucket — refill happens on read, no scheduler thread. Single-node only.
 * For distributed rate limiting you'd back this with a Redis Lua script that atomically
 * does (refill → check → decrement), keyed by the dimension you're throttling on.
 */
public class TokenBucket {

    private final long capacity;
    private final long refillTokens;
    private final long refillIntervalMillis;
    private double tokens;
    private long lastRefillMillis;

    public TokenBucket(Tier tier) {
        this.capacity = tier.capacity();
        this.refillTokens = tier.refillTokens();
        this.refillIntervalMillis = tier.refillIntervalMillis();
        this.tokens = capacity;
        this.lastRefillMillis = System.currentTimeMillis();
    }

    public synchronized ConsumeResult tryConsume(long n) {
        refill();
        if (tokens >= n) {
            tokens -= n;
            return new ConsumeResult(true, (long) Math.floor(tokens), capacity, 0L);
        }
        double deficit = n - tokens;
        double tokensPerMs = (double) refillTokens / refillIntervalMillis;
        long retryMillis = (long) Math.ceil(deficit / tokensPerMs);
        return new ConsumeResult(false, (long) Math.floor(tokens), capacity, retryMillis);
    }

    public synchronized Snapshot snapshot() {
        refill();
        return new Snapshot((long) Math.floor(tokens), capacity);
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefillMillis;
        if (elapsed <= 0) return;
        double tokensToAdd = ((double) elapsed / refillIntervalMillis) * refillTokens;
        tokens = Math.min(capacity, tokens + tokensToAdd);
        lastRefillMillis = now;
    }

    public record ConsumeResult(boolean allowed, long remaining, long capacity, long retryAfterMillis) {}
    public record Snapshot(long remaining, long capacity) {}
}
