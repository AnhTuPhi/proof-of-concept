package com.demo.patterns.distributedlock;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simulates a single "Redis" node holding locks with a TTL.
 * Real Redlock would talk to N independent Redis instances; here each LockNode
 * is an independent in-memory map so the quorum logic in {@link RedlockManager}
 * is exercised faithfully.
 */
public class LockNode {

    private final String name;
    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final AtomicBoolean down = new AtomicBoolean(false);

    public LockNode(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public boolean isDown() {
        return down.get();
    }

    public void setDown(boolean down) {
        this.down.set(down);
    }

    /** SET key value NX PX ttlMs — succeeds only if no live entry exists. */
    public boolean tryAcquire(String key, String token, long ttlMs) {
        if (down.get()) {
            throw new NodeUnavailableException(name);
        }
        long expiresAt = System.currentTimeMillis() + ttlMs;
        Entry desired = new Entry(token, expiresAt);
        Entry prior = store.compute(key, (k, current) -> {
            if (current == null || current.expiresAt <= System.currentTimeMillis()) {
                return desired;
            }
            return current;
        });
        return prior == desired;
    }

    /** Release only if our token still owns the key (Lua-script equivalent). */
    public boolean release(String key, String token) {
        if (down.get()) {
            return false;
        }
        return store.computeIfPresent(key, (k, current) -> {
            if (Objects.equals(current.token, token)) {
                return null;
            }
            return current;
        }) == null;
    }

    record Entry(String token, long expiresAt) {
        Instant expiresAtInstant() {
            return Instant.ofEpochMilli(expiresAt);
        }
    }

    public static class NodeUnavailableException extends RuntimeException {
        public NodeUnavailableException(String name) {
            super("Node " + name + " is down");
        }
    }
}
