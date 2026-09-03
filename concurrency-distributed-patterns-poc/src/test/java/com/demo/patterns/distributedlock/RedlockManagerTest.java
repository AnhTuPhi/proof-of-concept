package com.demo.patterns.distributedlock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RedlockManagerTest {

    private RedlockManager redlock;

    @BeforeEach
    void setUp() {
        redlock = new RedlockManager(5, 3, 5);
        redlock.init();
    }

    @Test
    void acquires_when_all_nodes_available() {
        Optional<RedlockManager.Lease> lease = redlock.tryAcquire("k", 1000);
        assertTrue(lease.isPresent());
        assertEquals(5, lease.get().acquiredOn().size());
        redlock.release(lease.get());
    }

    @Test
    void second_acquire_fails_while_first_held() {
        var first = redlock.tryAcquire("k", 1000).orElseThrow();
        Optional<RedlockManager.Lease> second = redlock.tryAcquire("k", 1000);
        assertTrue(second.isEmpty(), "second acquire should fail while first lease is held");
        redlock.release(first);
    }

    @Test
    void survives_minority_node_failure() {
        redlock.nodes().get(0).setDown(true);
        redlock.nodes().get(1).setDown(true);
        Optional<RedlockManager.Lease> lease = redlock.tryAcquire("k", 1000);
        assertTrue(lease.isPresent(), "quorum of 3/5 still reachable with 2 nodes down");
        redlock.release(lease.get());
    }

    @Test
    void fails_when_majority_down() {
        redlock.nodes().get(0).setDown(true);
        redlock.nodes().get(1).setDown(true);
        redlock.nodes().get(2).setDown(true);
        Optional<RedlockManager.Lease> lease = redlock.tryAcquire("k", 1000);
        assertTrue(lease.isEmpty(), "no quorum: 3 of 5 nodes down");
    }

    @Test
    void mutual_exclusion_under_contention() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                Optional<RedlockManager.Lease> l = redlock.tryAcquire("k", 200);
                if (l.isPresent()) {
                    acquired.incrementAndGet();
                    Thread.sleep(50);
                    redlock.release(l.get());
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(acquired.get() >= 1, "at least one thread should have acquired");
        assertTrue(acquired.get() < threads, "not every concurrent attempt should win");
    }
}
