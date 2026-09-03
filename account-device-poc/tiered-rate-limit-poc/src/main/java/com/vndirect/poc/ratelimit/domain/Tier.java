package com.vndirect.poc.ratelimit.domain;

/**
 * Tiers map to (capacity, refillTokens, refillIntervalMillis) — a token-bucket config.
 * - capacity:    max burst the user can spike to in a single instant.
 * - refillTokens / refillInterval: steady-state rate.
 * Real systems read this from a tier_quotas table so plan upgrades are runtime, not redeploy.
 */
public enum Tier {
    FREE(20, 20, 60_000L),           // 20 req per minute, burst 20
    PRO(120, 120, 60_000L),          // 120 req per minute, burst 120
    ENTERPRISE(600, 600, 60_000L);   // 600 req per minute, soft-cap (we still return 429)

    private final long capacity;
    private final long refillTokens;
    private final long refillIntervalMillis;

    Tier(long capacity, long refillTokens, long refillIntervalMillis) {
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.refillIntervalMillis = refillIntervalMillis;
    }

    public long capacity() { return capacity; }
    public long refillTokens() { return refillTokens; }
    public long refillIntervalMillis() { return refillIntervalMillis; }
}
