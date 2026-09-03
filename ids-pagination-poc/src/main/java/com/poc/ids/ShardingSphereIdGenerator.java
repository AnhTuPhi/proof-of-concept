package com.poc.ids;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Apache ShardingSphere snowflake key generator.
 * Reference:
 *   https://shardingsphere.apache.org/document/current/en/user-manual/common-config/builtin-algorithm/keygen/
 *
 * Bit layout is identical to Twitter Snowflake:
 *   1 bit  : sign
 *   41 bits: timestamp (ms since epoch)
 *   10 bits: worker id
 *   12 bits: sequence
 *
 * Two production-hardening features added on top of vanilla Snowflake:
 *
 *  1. **Clock-back tolerance** ({@code maxTolerateClockBackMillis}).
 *     Vanilla Snowflake throws immediately when the wall clock moves backwards
 *     (NTP correction, VM pause, leap second). ShardingSphere instead waits
 *     up to N ms hoping the clock catches up; only after that timeout does it
 *     surface the error.
 *
 *  2. **Sequence vibration** ({@code maxVibrationOffset}).
 *     When the sequence overflows within a ms (rare but possible under
 *     extreme load), the next ms restarts the sequence at a small randomized
 *     offset instead of always at 0. This avoids a pathological pattern when
 *     downstream sharding hashes IDs by {@code id % N} — without vibration
 *     every ms's first ID lands on the same shard, creating a hot spot.
 *
 * Both features are tunable; pass 0 to disable.
 */
public final class ShardingSphereIdGenerator {

    private static final long EPOCH_MILLIS = 1704067200000L;

    private static final long WORKER_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;

    public static final long MAX_WORKER_ID = (1L << WORKER_BITS) - 1;
    public static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    private static final long WORKER_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS;

    private final long workerId;
    private final long maxTolerateClockBackMillis;
    private final int maxVibrationOffset;

    private long lastTimestamp = -1L;
    private long sequence = 0L;
    private int sequenceOffset = 0;

    /**
     * @param workerId                   0..1023, must be unique per generator
     * @param maxTolerateClockBackMillis how long to wait if the clock rewinds (default in
     *                                   ShardingSphere is 10 ms; 0 = throw immediately)
     * @param maxVibrationOffset         0..MAX_SEQUENCE; 0 disables vibration. Default 1
     *                                   in ShardingSphere; larger spreads IDs over more
     *                                   shards but costs sequence headroom per ms.
     */
    public ShardingSphereIdGenerator(long workerId,
                                     long maxTolerateClockBackMillis,
                                     int maxVibrationOffset) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be 0.." + MAX_WORKER_ID);
        }
        if (maxTolerateClockBackMillis < 0) {
            throw new IllegalArgumentException("maxTolerateClockBackMillis must be >= 0");
        }
        if (maxVibrationOffset < 0 || maxVibrationOffset > MAX_SEQUENCE) {
            throw new IllegalArgumentException(
                "maxVibrationOffset must be 0.." + MAX_SEQUENCE);
        }
        this.workerId = workerId;
        this.maxTolerateClockBackMillis = maxTolerateClockBackMillis;
        this.maxVibrationOffset = maxVibrationOffset;
    }

    /** Defaults match ShardingSphere's out-of-the-box config. */
    public ShardingSphereIdGenerator(long workerId) {
        this(workerId, 10L, 1);
    }

    public synchronized long nextId() {
        long now = waitIfClockBack(System.currentTimeMillis());

        if (now == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                now = waitNextMillis(lastTimestamp);
                sequence = nextVibration();
            }
        } else {
            sequence = nextVibration();
        }

        lastTimestamp = now;

        return ((now - EPOCH_MILLIS) << TIMESTAMP_SHIFT)
            | (workerId << WORKER_SHIFT)
            | sequence;
    }

    /**
     * If the wall clock has moved backwards, busy-wait up to
     * {@code maxTolerateClockBackMillis}. If still behind after that, fail loudly.
     */
    private long waitIfClockBack(long now) {
        if (now >= lastTimestamp) return now;

        long diff = lastTimestamp - now;
        if (diff > maxTolerateClockBackMillis) {
            throw new IllegalStateException(
                "Clock moved backwards by " + diff
                    + " ms (tolerance is " + maxTolerateClockBackMillis + " ms)");
        }
        // Sleep + recheck up to the tolerance budget.
        try {
            Thread.sleep(diff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while tolerating clock-back", e);
        }
        now = System.currentTimeMillis();
        if (now < lastTimestamp) {
            throw new IllegalStateException(
                "Clock still behind after tolerance wait: " + (lastTimestamp - now) + " ms");
        }
        return now;
    }

    /**
     * Start each ms at a randomized offset within {@code [0, maxVibrationOffset]}
     * so that ID-mod-N hashing doesn't always land on the same shard.
     */
    private long nextVibration() {
        if (maxVibrationOffset == 0) return 0L;
        sequenceOffset = (sequenceOffset + 1) % (maxVibrationOffset + 1);
        // Mix with a small random component for extra distribution.
        return (sequenceOffset + ThreadLocalRandom.current().nextInt(0, maxVibrationOffset + 1))
            & MAX_SEQUENCE;
    }

    private long waitNextMillis(long lastTs) {
        long now = System.currentTimeMillis();
        while (now <= lastTs) {
            now = System.currentTimeMillis();
        }
        return now;
    }

    public static Instant timestampOf(long id) {
        return Instant.ofEpochMilli((id >>> TIMESTAMP_SHIFT) + EPOCH_MILLIS);
    }

    public static long workerOf(long id) {
        return (id >>> WORKER_SHIFT) & MAX_WORKER_ID;
    }

    public static long sequenceOf(long id) {
        return id & MAX_SEQUENCE;
    }
}
