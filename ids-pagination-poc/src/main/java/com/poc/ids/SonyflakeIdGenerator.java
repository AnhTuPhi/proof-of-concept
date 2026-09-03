package com.poc.ids;

import java.time.Instant;

/**
 * Sonyflake — Sony's variation on Snowflake.
 * Reference: https://github.com/sony/sonyflake
 *
 * 64-bit layout:
 *   1 bit  : unused
 *   39 bits: timestamp (10 ms units since custom epoch) → ~174 years
 *   8 bits : sequence (0..255 per 10 ms per machine)
 *   16 bits: machine id (0..65,535)
 *
 * Trade-off vs Snowflake:
 *   + 174 yr range (vs 69 yr)
 *   + 65,536 machines (vs 1,024)
 *   − ~25,600 IDs/sec/machine (vs ~4M for Snowflake)
 *
 * Use when you have many small services for a very long time, not for
 * a few hot services needing maximum per-process throughput.
 */
public final class SonyflakeIdGenerator {

    /** 2024-01-01T00:00:00Z — pick your own at deploy time. */
    private static final long EPOCH_MILLIS = 1704067200000L;

    private static final long TIME_BITS = 39L;
    private static final long SEQUENCE_BITS = 8L;
    private static final long MACHINE_BITS = 16L;

    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;  // 255
    private static final long MAX_MACHINE_ID = (1L << MACHINE_BITS) - 1; // 65,535
    private static final long MAX_ELAPSED_TIME = (1L << TIME_BITS) - 1;  // ~174 yr in 10ms units

    private static final long SEQUENCE_SHIFT = MACHINE_BITS;
    private static final long TIME_SHIFT = MACHINE_BITS + SEQUENCE_BITS;

    /** Sonyflake ticks every 10 ms. */
    private static final long TICK_MS = 10L;

    private final long machineId;

    private long lastElapsedTime = -1L;
    private long sequence = 0L;

    public SonyflakeIdGenerator(long machineId) {
        if (machineId < 0 || machineId > MAX_MACHINE_ID) {
            throw new IllegalArgumentException("machineId must be 0.." + MAX_MACHINE_ID);
        }
        this.machineId = machineId;
    }

    public synchronized long nextId() {
        long elapsed = currentElapsed();

        if (elapsed < lastElapsedTime) {
            throw new IllegalStateException(
                "Clock moved backwards by " + ((lastElapsedTime - elapsed) * TICK_MS) + " ms");
        }
        if (elapsed > MAX_ELAPSED_TIME) {
            throw new IllegalStateException(
                "Sonyflake epoch exhausted — pick a newer EPOCH_MILLIS");
        }

        if (elapsed == lastElapsedTime) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                elapsed = waitNextTick(lastElapsedTime);
            }
        } else {
            sequence = 0L;
        }

        lastElapsedTime = elapsed;

        return (elapsed << TIME_SHIFT)
            | (sequence << SEQUENCE_SHIFT)
            | machineId;
    }

    private static long currentElapsed() {
        return (System.currentTimeMillis() - EPOCH_MILLIS) / TICK_MS;
    }

    private long waitNextTick(long lastTick) {
        long elapsed = currentElapsed();
        while (elapsed <= lastTick) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for next tick", e);
            }
            elapsed = currentElapsed();
        }
        return elapsed;
    }

    public static Instant timestampOf(long id) {
        long ticks = id >>> TIME_SHIFT;
        return Instant.ofEpochMilli(EPOCH_MILLIS + ticks * TICK_MS);
    }

    public static long sequenceOf(long id) {
        return (id >>> SEQUENCE_SHIFT) & MAX_SEQUENCE;
    }

    public static long machineIdOf(long id) {
        return id & MAX_MACHINE_ID;
    }
}
