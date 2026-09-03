package com.poc;

import com.poc.ids.InstagramIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InstagramIdGeneratorTest {

    @Test
    void encodesShardAndTimestamp() {
        var gen = new InstagramIdGenerator();
        Instant before = Instant.now();
        long id = gen.nextId(42);
        Instant after = Instant.now();

        assertEquals(42L, InstagramIdGenerator.shardOf(id));
        Instant ts = InstagramIdGenerator.timestampOf(id);
        assertFalse(ts.isBefore(before.minusMillis(1)));
        assertFalse(ts.isAfter(after.plusMillis(1)));
    }

    @Test
    void perShardSequencesAreIndependent() {
        // Two shards have separate counters, so the first ID on each shard
        // starts at sequence=0 regardless of how much traffic the other shard
        // has seen. (Subsequent calls may roll to a new ms, so we don't pin
        // exact sequence values past the first one.)
        var gen = new InstagramIdGenerator();

        long firstOnShard5 = gen.nextId(5);
        long firstOnShard6 = gen.nextId(6);

        assertEquals(0L, InstagramIdGenerator.sequenceOf(firstOnShard5));
        assertEquals(0L, InstagramIdGenerator.sequenceOf(firstOnShard6));
        assertEquals(5L, InstagramIdGenerator.shardOf(firstOnShard5));
        assertEquals(6L, InstagramIdGenerator.shardOf(firstOnShard6));
        assertNotEquals(firstOnShard5, firstOnShard6);
    }

    @Test
    void rejectsOutOfRangeShard() {
        var gen = new InstagramIdGenerator();
        assertThrows(IllegalArgumentException.class, () -> gen.nextId(-1));
        assertThrows(IllegalArgumentException.class, () -> gen.nextId(8192));
    }

    @Test
    void supportsFullShardRange() {
        var gen = new InstagramIdGenerator();
        long topShard = InstagramIdGenerator.MAX_SHARD_ID;
        long id = gen.nextId(topShard);
        assertEquals(topShard, InstagramIdGenerator.shardOf(id));
    }

    @Test
    void manyIdsAreUniquePerShard() {
        var gen = new InstagramIdGenerator();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            assertTrue(seen.add(gen.nextId(7)), "duplicate id on single shard");
        }
    }

    @Test
    void uniqueAcrossShardsAndThreads() throws Exception {
        var gen = new InstagramIdGenerator();
        int threads = 8;
        int perThread = 2_000;
        var seen = ConcurrentHashMap.<Long>newKeySet(threads * perThread);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            final long shard = t; // each thread on its own shard
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    assertTrue(seen.add(gen.nextId(shard)),
                        "duplicate id under contention");
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(threads * perThread, seen.size());
    }
}
