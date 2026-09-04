package com.example.espoc.common.id;

import com.github.f4b6a3.ulid.Ulid;
import com.github.f4b6a3.ulid.UlidCreator;

import java.time.Instant;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Two ID strategies used across the suite.
 *
 * - {@link #ulid()} — time-sortable 128-bit. Crockford base32. Use as ES {@code _id} when you want
 *   inserts to roughly land in chronological order (helps search_after pagination, helps merges).
 * - {@link #snowflake()} — 64-bit time-sortable long. Use when an int64 PK matters (smaller index size,
 *   faster sorts) and you can tolerate 41-bit timestamp wrap in ~70 years.
 */
public final class IdGenerators {

    private IdGenerators() {}

    public static String ulid() {
        return UlidCreator.getMonotonicUlid().toString();
    }

    public static String ulidAt(Instant when) {
        return Ulid.from(when.toEpochMilli(), randomBytes()).toString();
    }

    public static long snowflake() {
        return SnowflakeIdGenerator.INSTANCE.nextId();
    }

    private static byte[] randomBytes() {
        byte[] b = new byte[10];
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(b);
        return b;
    }

    /**
     * Minimal snowflake — 41-bit timestamp (ms since custom epoch), 10-bit machine, 12-bit sequence.
     * For a POC we pick machine id from MAC hash; in production you'd wire it from config/ZooKeeper.
     */
    static final class SnowflakeIdGenerator {
        static final SnowflakeIdGenerator INSTANCE = new SnowflakeIdGenerator(
                Math.abs(java.util.UUID.randomUUID().hashCode() % 1024));

        private static final long EPOCH = 1_704_067_200_000L; // 2024-01-01T00:00:00Z
        private static final long MACHINE_BITS = 10;
        private static final long SEQUENCE_BITS = 12;
        private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

        private final long machineId;
        private final Lock lock = new ReentrantLock();
        private long lastTimestamp = -1L;
        private long sequence = 0L;

        SnowflakeIdGenerator(int machineId) {
            this.machineId = machineId & ((1L << MACHINE_BITS) - 1);
        }

        long nextId() {
            lock.lock();
            try {
                long ts = System.currentTimeMillis();
                if (ts < lastTimestamp) {
                    // Clock moved backward — wait it out
                    ts = lastTimestamp;
                }
                if (ts == lastTimestamp) {
                    sequence = (sequence + 1) & MAX_SEQUENCE;
                    if (sequence == 0) {
                        // Sequence exhausted in this millisecond — spin to next ms
                        while ((ts = System.currentTimeMillis()) <= lastTimestamp) { /* spin */ }
                    }
                } else {
                    sequence = 0L;
                }
                lastTimestamp = ts;
                return ((ts - EPOCH) << (MACHINE_BITS + SEQUENCE_BITS))
                        | (machineId << SEQUENCE_BITS)
                        | sequence;
            } finally {
                lock.unlock();
            }
        }
    }
}
