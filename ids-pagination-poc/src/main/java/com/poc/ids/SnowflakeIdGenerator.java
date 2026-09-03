package com.poc.ids;

import java.time.Instant;

/**
 * Twitter-style Snowflake ID generator.
 *
 * 64-bit layout:
 *   1 bit  : sign (always 0)
 *   41 bits: timestamp (ms since custom epoch) — ~69 years range
 *   10 bits: machine id (datacenter 5 + worker 5)
 *   12 bits: sequence (0..4095 per ms per machine)
 *
 * Monotonically increasing within a single generator; sortable by time.
 */
public final class SnowflakeIdGenerator {

    private static final long EPOCH_MILLIS = 1704067200000L;

    private static final long DATACENTER_BITS = 5L;
    private static final long WORKER_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_DATACENTER_ID = (1L << DATACENTER_BITS) - 1;
    private static final long MAX_WORKER_ID = (1L << WORKER_BITS) - 1;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    private static final long WORKER_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_SHIFT = SEQUENCE_BITS + WORKER_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS + DATACENTER_BITS;

    private final long datacenterId;
    private final long workerId;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(long datacenterId, long workerId) {
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException("datacenterId must be 0.." + MAX_DATACENTER_ID);
        }
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be 0.." + MAX_WORKER_ID);
        }
        this.datacenterId = datacenterId;
        this.workerId = workerId;
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

        return ((now - EPOCH_MILLIS) << TIMESTAMP_SHIFT)
            | (datacenterId << DATACENTER_SHIFT)
            | (workerId << WORKER_SHIFT)
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
        long ms = (id >> TIMESTAMP_SHIFT) + EPOCH_MILLIS;
        return Instant.ofEpochMilli(ms);
    }

    public static long datacenterOf(long id) {
        return (id >> DATACENTER_SHIFT) & MAX_DATACENTER_ID;
    }

    public static long workerOf(long id) {
        return (id >> WORKER_SHIFT) & MAX_WORKER_ID;
    }

    public static long sequenceOf(long id) {
        return id & MAX_SEQUENCE;
    }
}
