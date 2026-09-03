package com.poc;

import com.poc.ids.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SnowflakeIdGeneratorTest {

    @Test
    void idsAreStrictlyIncreasing() {
        var gen = new SnowflakeIdGenerator(3, 7);
        long prev = -1;
        for (int i = 0; i < 10_000; i++) {
            long id = gen.nextId();
            assertTrue(id > prev, "id must increase");
            prev = id;
        }
    }

    @Test
    void idsAreUnique() {
        var gen = new SnowflakeIdGenerator(0, 0);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            assertTrue(seen.add(gen.nextId()));
        }
    }

    @Test
    void decodesDatacenterWorkerAndTimestamp() {
        var gen = new SnowflakeIdGenerator(13, 21);
        Instant before = Instant.now();
        long id = gen.nextId();
        Instant after = Instant.now();

        assertEquals(13, SnowflakeIdGenerator.datacenterOf(id));
        assertEquals(21, SnowflakeIdGenerator.workerOf(id));

        Instant ts = SnowflakeIdGenerator.timestampOf(id);
        assertFalse(ts.isBefore(before.minusMillis(1)));
        assertFalse(ts.isAfter(after.plusMillis(1)));
        assertTrue(Duration.between(ts, after).toSeconds() < 2);
    }

    @Test
    void rejectsOutOfRangeIds() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(32, 0));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(0, 32));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(-1, 0));
    }

    @Test
    void uniqueAcrossThreads() throws Exception {
        var gen = new SnowflakeIdGenerator(2, 4);
        int threads = 8;
        int perThread = 5_000;
        var seen = ConcurrentHashMap.<Long>newKeySet(threads * perThread);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    assertTrue(seen.add(gen.nextId()), "duplicate id under contention");
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(threads * perThread, seen.size());
    }
}
