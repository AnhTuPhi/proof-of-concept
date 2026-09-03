package com.demo.patterns.distributedlock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/demo/redlock")
public class RedlockController {

    private static final Logger log = LoggerFactory.getLogger(RedlockController.class);

    private final RedlockManager redlock;

    public RedlockController(RedlockManager redlock) {
        this.redlock = redlock;
    }

    /**
     * Try to acquire the lock for {@code key}, hold it for {@code workMs}, then release.
     * Concurrent calls with the same key will see only one winner at a time.
     */
    @PostMapping("/work")
    public ResponseEntity<Map<String, Object>> work(
            @RequestParam String key,
            @RequestParam(defaultValue = "worker") String workerId,
            @RequestParam(defaultValue = "500") long workMs,
            @RequestParam(defaultValue = "3000") long ttlMs) throws InterruptedException {

        Instant requestedAt = Instant.now();
        Optional<RedlockManager.Lease> maybe = redlock.tryAcquire(key, ttlMs);
        if (maybe.isEmpty()) {
            return ResponseEntity.status(409).body(Map.of(
                    "acquired", false,
                    "workerId", workerId,
                    "key", key,
                    "requestedAt", requestedAt.toString()
            ));
        }
        RedlockManager.Lease lease = maybe.get();
        try {
            Thread.sleep(workMs);
            return ResponseEntity.ok(Map.of(
                    "acquired", true,
                    "workerId", workerId,
                    "key", key,
                    "token", lease.token(),
                    "acquiredOnNodes", lease.acquiredOn(),
                    "validityMs", lease.validityMs(),
                    "workMs", workMs
            ));
        } finally {
            redlock.release(lease);
            log.info("Released lock '{}' for worker {}", key, workerId);
        }
    }

    /** Toggle a simulated node failure to demonstrate quorum tolerance. */
    @PostMapping("/nodes/{name}/down")
    public ResponseEntity<Map<String, Object>> setNodeDown(
            @PathVariable String name,
            @RequestParam boolean down) {
        LockNode target = redlock.nodes().stream()
                .filter(n -> n.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown node: " + name));
        target.setDown(down);
        return ResponseEntity.ok(Map.of("node", name, "down", down));
    }

    @GetMapping("/nodes")
    public List<Map<String, Object>> listNodes() {
        return redlock.nodes().stream()
                .map(n -> Map.<String, Object>of("name", n.name(), "down", n.isDown()))
                .toList();
    }
}
