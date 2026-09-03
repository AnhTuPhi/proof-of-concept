package com.demo.patterns.distributedlock;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Redlock — Antirez's algorithm for acquiring a lock across N independent stores.
 *
 * Acquire:
 *   1. Pick a unique token.
 *   2. Try to SET NX PX on each of the N nodes, in parallel-ish.
 *   3. If at least quorum (N/2 + 1) succeeded AND elapsed time < ttl - drift,
 *      the lock is held with validity = ttl - elapsed - drift.
 *   4. Otherwise release everywhere and fail.
 *
 * Release: best-effort delete-if-token-matches on every node.
 */
@Component
public class RedlockManager {

    private static final Logger log = LoggerFactory.getLogger(RedlockManager.class);

    private final int nodeCount;
    private final int quorum;
    private final long clockDriftMs;
    private final List<LockNode> nodes = new ArrayList<>();

    public RedlockManager(
            @Value("${demo.redlock.nodes:5}") int nodeCount,
            @Value("${demo.redlock.quorum:3}") int quorum,
            @Value("${demo.redlock.clock-drift-ms:5}") long clockDriftMs) {
        this.nodeCount = nodeCount;
        this.quorum = quorum;
        this.clockDriftMs = clockDriftMs;
    }

    @PostConstruct
    void init() {
        for (int i = 0; i < nodeCount; i++) {
            nodes.add(new LockNode("node-" + i));
        }
        log.info("Redlock initialised with {} nodes, quorum={}, drift={}ms",
                nodeCount, quorum, clockDriftMs);
    }

    public List<LockNode> nodes() {
        return List.copyOf(nodes);
    }

    public Optional<Lease> tryAcquire(String key, long ttlMs) {
        String token = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        int acquired = 0;
        List<String> acquiredOn = new ArrayList<>();
        for (LockNode node : nodes) {
            try {
                if (node.tryAcquire(key, token, ttlMs)) {
                    acquired++;
                    acquiredOn.add(node.name());
                }
            } catch (LockNode.NodeUnavailableException e) {
                log.warn("Node {} unavailable during acquire of '{}'", node.name(), key);
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        long validity = ttlMs - elapsed - clockDriftMs;

        if (acquired >= quorum && validity > 0) {
            log.info("Acquired lock '{}' token={} on {}/{} nodes (validity={}ms)",
                    key, token, acquired, nodes.size(), validity);
            return Optional.of(new Lease(key, token, validity, acquiredOn));
        }
        log.warn("Failed to acquire '{}' (got {}/{}, validity={}ms) — releasing partial",
                key, acquired, nodes.size(), validity);
        releaseEverywhere(key, token);
        return Optional.empty();
    }

    public void release(Lease lease) {
        releaseEverywhere(lease.key(), lease.token());
    }

    private void releaseEverywhere(String key, String token) {
        for (LockNode node : nodes) {
            try {
                node.release(key, token);
            } catch (RuntimeException e) {
                log.debug("Release on {} failed: {}", node.name(), e.toString());
            }
        }
    }

    public record Lease(String key, String token, long validityMs, List<String> acquiredOn) {}
}
