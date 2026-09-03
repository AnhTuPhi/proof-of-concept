package com.poc;

import com.poc.ids.SonyflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SonyflakeIdGeneratorTest {

    @Test
    void idsAreStrictlyIncreasing() {
        var gen = new SonyflakeIdGenerator(42);
        long prev = -1;
        for (int i = 0; i < 500; i++) {
            long id = gen.nextId();
            assertTrue(id > prev, "Sonyflake ids must increase");
            prev = id;
        }
    }

    @Test
    void idsAreUnique() {
        var gen = new SonyflakeIdGenerator(1);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            assertTrue(seen.add(gen.nextId()), "duplicate id");
        }
    }

    @Test
    void encodesMachineIdAndTimestamp() {
        var gen = new SonyflakeIdGenerator(12345);
        Instant before = Instant.now();
        long id = gen.nextId();
        Instant after = Instant.now();

        assertEquals(12345L, SonyflakeIdGenerator.machineIdOf(id));

        Instant ts = SonyflakeIdGenerator.timestampOf(id);
        // 10ms granularity → allow ±20ms slack
        assertFalse(ts.isBefore(before.minusMillis(20)));
        assertFalse(ts.isAfter(after.plusMillis(20)));
    }

    @Test
    void rejectsOutOfRangeMachineId() {
        assertThrows(IllegalArgumentException.class, () -> new SonyflakeIdGenerator(-1));
        assertThrows(IllegalArgumentException.class, () -> new SonyflakeIdGenerator(65_536));
    }

    @Test
    void supports16BitMachineIdSpace() {
        // Top end of the machine-id range — proves we get the full 65,536 slots.
        var gen = new SonyflakeIdGenerator(65_535);
        long id = gen.nextId();
        assertEquals(65_535L, SonyflakeIdGenerator.machineIdOf(id));
    }

    @Test
    void uniqueAcrossThreads() throws Exception {
        var gen = new SonyflakeIdGenerator(7);
        int threads = 8;
        int perThread = 200;
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
