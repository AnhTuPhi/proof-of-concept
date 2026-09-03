package com.demo.resilience.idempotency;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory idempotency cache. In production, back this with Redis
 * or a database table with a unique constraint on (key, request_hash).
 * The body hash protects against key reuse with a different payload —
 * we return 409 in that case rather than silently replaying a stale response.
 */
@Component
public class IdempotencyStore {

    public record Entry(int status, String body, String requestHash, Instant expiresAt) {}

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final Duration ttl = Duration.ofMinutes(10);

    public Optional<Entry> get(String key) {
        Entry e = store.get(key);
        if (e == null) return Optional.empty();
        if (Instant.now().isAfter(e.expiresAt())) {
            store.remove(key, e);
            return Optional.empty();
        }
        return Optional.of(e);
    }

    public void put(String key, int status, String body, String requestHash) {
        store.put(key, new Entry(status, body, requestHash, Instant.now().plus(ttl)));
    }

    public int size() {
        return store.size();
    }
}
