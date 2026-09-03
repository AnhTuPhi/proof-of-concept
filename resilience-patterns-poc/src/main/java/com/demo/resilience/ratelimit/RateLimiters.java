package com.demo.resilience.ratelimit;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-client limiters. We key by an opaque clientId — in real life that's an
 * API key, a JWT subject, or the source IP after trusted-proxy stripping.
 *
 * Defaults:
 *   token bucket  — burst 10, 5 req/s sustained
 *   sliding log   — 20 req per 10s window
 */
@Component
public class RateLimiters {

    private final ConcurrentMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SlidingWindowLog> windows = new ConcurrentHashMap<>();

    public TokenBucket bucketFor(String clientId) {
        return buckets.computeIfAbsent(clientId, k -> new TokenBucket(10, 5));
    }

    public SlidingWindowLog windowFor(String clientId) {
        return windows.computeIfAbsent(clientId, k -> new SlidingWindowLog(20, Duration.ofSeconds(10)));
    }
}
