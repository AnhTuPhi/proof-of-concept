package com.poc;

import com.poc.ids.ShardingSphereIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ShardingSphereIdGeneratorTest {

    @Test
    void idsAreUniqueAndMostlyIncreasing() {
        // With vibration enabled, IDs within the same ms can briefly dip
        // (sequence vibrates), but cross-ms they are strictly increasing
        // and every value is unique.
        var gen = new ShardingSphereIdGenerator(7);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            assertTrue(seen.add(gen.nextId()), "duplicate id");
        }
    }

    @Test
    void strictlyIncreasingWithoutVibration() {
        // Disable vibration → sequence resets to 0 each ms, strictly monotonic.
        var gen = new ShardingSphereIdGenerator(0, 0L, 0);
        long prev = -1L;
        for (int i = 0; i < 10_000; i++) {
            long id = gen.nextId();
            assertTrue(id > prev, "must be strictly increasing without vibration");
            prev = id;
        }
    }

    @Test
    void encodesWorkerAndTimestamp() {
        var gen = new ShardingSphereIdGenerator(513);
        Instant before = Instant.now();
        long id = gen.nextId();
        Instant after = Instant.now();

        assertEquals(513L, ShardingSphereIdGenerator.workerOf(id));
        Instant ts = ShardingSphereIdGenerator.timestampOf(id);
        assertFalse(ts.isBefore(before.minusMillis(1)));
        assertFalse(ts.isAfter(after.plusMillis(1)));
    }

    @Test
    void rejectsBadConfig() {
        assertThrows(IllegalArgumentException.class,
            () -> new ShardingSphereIdGenerator(1024, 10L, 1));
        assertThrows(IllegalArgumentException.class,
            () -> new ShardingSphereIdGenerator(-1));
        assertThrows(IllegalArgumentException.class,
            () -> new ShardingSphereIdGenerator(0, -1L, 1));
        assertThrows(IllegalArgumentException.class,
            () -> new ShardingSphereIdGenerator(0, 10L, 4096));
    }

    @Test
    void supportsFullWorkerRange() {
        var gen = new ShardingSphereIdGenerator(ShardingSphereIdGenerator.MAX_WORKER_ID, 0L, 0);
        long id = gen.nextId();
        assertEquals(ShardingSphereIdGenerator.MAX_WORKER_ID,
            ShardingSphereIdGenerator.workerOf(id));
    }

    @Test
    void vibrationSpreadsSequenceAcrossShards() {
        // With vibration enabled, the first ID of consecutive ms windows should
        // NOT always have sequence==0. We verify by counting distinct first-of-ms
        // sequences across many IDs.
        var gen = new ShardingSphereIdGenerator(0, 10L, 7);
        Set<Long> firstOfMsSequences = new HashSet<>();
        long lastTs = -1L;
        for (int i = 0; i < 5_000; i++) {
            long id = gen.nextId();
            long ts = ShardingSphereIdGenerator.timestampOf(id).toEpochMilli();
            if (ts != lastTs) {
                firstOfMsSequences.add(ShardingSphereIdGenerator.sequenceOf(id));
                lastTs = ts;
            }
        }
        // Without vibration this set would always be {0}. With vibration it
        // spans multiple values.
        assertTrue(firstOfMsSequences.size() > 1,
            "vibration should produce varied first-of-ms sequences; got " + firstOfMsSequences);
    }

    @Test
    void uniqueAcrossThreads() throws Exception {
        var gen = new ShardingSphereIdGenerator(11);
        int threads = 8;
        int perThread = 2_000;
        var seen = ConcurrentHashMap.<Long>newKeySet(threads * perThread);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    assertTrue(seen.add(gen.nextId()), "duplicate under contention");
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(threads * perThread, seen.size());
    }
}
