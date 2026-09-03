package com.poc.ids;

import java.time.Instant;

/**
 * Discord Snowflake ID generator.
 *
 * Discord uses the same 64-bit Snowflake structure as Twitter, with three
 * twists that matter in practice:
 *
 *   • Custom epoch: 2015-01-01T00:00:00Z (1420070400000 ms).
 *     Real Discord IDs were minted starting then, so the high bits encode
 *     a time-since-Discord-launched value instead of Unix epoch.
 *   • Machine bits split as 5 worker + 5 process (instead of Twitter's
 *     5 datacenter + 5 worker). Same total, different naming convention.
 *   • Sequence is per-(worker, process), 12 bits, 4096 IDs/ms.
 *
 * 64-bit layout:
 *   1 bit  : sign (always 0)
 *   41 bits: timestamp (ms since Discord epoch) — runs until ~2084
 *   5 bits : internal worker id (0..31)
 *   5 bits : internal process id (0..31)
 *   12 bits: sequence (0..4095 per ms per process)
 *
 * Throughput per (worker, process): ~4M IDs/sec — same as Twitter Snowflake.
 *
 * Reference: https://discord.com/developers/docs/reference#snowflakes
 */
public final class DiscordIdGenerator {

    /** Discord epoch: 2015-01-01T00:00:00Z. */
    public static final long DISCORD_EPOCH = 1420070400000L;

    private static final long WORKER_BITS = 5L;
    private static final long PROCESS_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = (1L << WORKER_BITS) - 1;
    private static final long MAX_PROCESS_ID = (1L << PROCESS_BITS) - 1;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    private static final long PROCESS_SHIFT = SEQUENCE_BITS;
    private static final long WORKER_SHIFT = SEQUENCE_BITS + PROCESS_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + PROCESS_BITS + WORKER_BITS;

    private final long workerId;
    private final long processId;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public DiscordIdGenerator(long workerId, long processId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be 0.." + MAX_WORKER_ID);
        }
        if (processId < 0 || processId > MAX_PROCESS_ID) {
            throw new IllegalArgumentException("processId must be 0.." + MAX_PROCESS_ID);
        }
        this.workerId = workerId;
        this.processId = processId;
    }

    public synchronized long nextId() {
        long now = System.currentTimeMillis();

        if (now < lastTimestamp) {
            throw new IllegalStateException(
                "Clock moved backwards by " + (lastTimestamp - now) + " ms");
        }

        if (now == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                now = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = now;

        return ((now - DISCORD_EPOCH) << TIMESTAMP_SHIFT)
            | (workerId << WORKER_SHIFT)
            | (processId << PROCESS_SHIFT)
            | sequence;
    }

    private long waitNextMillis(long lastTs) {
        long now = System.currentTimeMillis();
        while (now <= lastTs) {
            now = System.currentTimeMillis();
        }
        return now;
    }

    public static Instant timestampOf(long id) {
        long ms = (id >>> TIMESTAMP_SHIFT) + DISCORD_EPOCH;
        return Instant.ofEpochMilli(ms);
    }

    public static long workerOf(long id) {
        return (id >>> WORKER_SHIFT) & MAX_WORKER_ID;
    }

    public static long processOf(long id) {
        return (id >>> PROCESS_SHIFT) & MAX_PROCESS_ID;
    }

    public static long sequenceOf(long id) {
        return id & MAX_SEQUENCE;
    }
}
