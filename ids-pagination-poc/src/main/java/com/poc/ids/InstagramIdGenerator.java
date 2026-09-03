package com.poc.ids;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Instagram-style sharded Snowflake ID.
 * Reference: https://instagram-engineering.com/sharding-ids-at-instagram-1cf5a71e5a5c
 *
 * 64-bit layout:
 *   1 bit  : sign (always 0)
 *   41 bits: timestamp (ms since custom epoch) — ~69 years
 *   13 bits: logical shard id (0..8191) — corresponds to a DB shard
 *   10 bits: per-shard sequence (0..1023, wraps every 1024 IDs)
 *
 * Key insight that differs from Twitter Snowflake:
 *   The sequence is **per shard**, sourced from a database sequence in the
 *   real Instagram design (PL/pgSQL: `SELECT nextval('shard.seq') % 1024`).
 *   Each logical shard has its own counter, so all writes routed to the
 *   same shard share an ID space that grows monotonically together.
 *
 * Why it matters: applications route a write to a shard (often via
 * user_id % numShards), then ask the shard to mint the ID. The shard
 * embedded in the ID lets you read it back from the right database without
 * any extra metadata lookup — "the ID knows where it lives."
 *
 * In Instagram's original Postgres design, the sequence wrapping every 1024
 * is safe because `nextval` itself is slow enough that no single shard can
 * exhaust the 1024 slots within a millisecond. In this in-process Java port
 * a tight loop *can* exhaust them, so we add a wait-for-next-ms guard when
 * the sequence wraps — same trick Twitter Snowflake uses.
 */
public final class InstagramIdGenerator {

    /** Instagram used 2011-09-01 as their epoch; we use 2024-01-01 here. */
    private static final long EPOCH_MILLIS = 1704067200000L;

    private static final long SHARD_BITS = 13L;
    private static final long SEQUENCE_BITS = 10L;

    public static final long MAX_SHARD_ID = (1L << SHARD_BITS) - 1;   // 8,191
    public static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1; // 1,023

    private static final long SHARD_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + SHARD_BITS;

    /** Per-shard state holders, lazily initialized. Locked individually so
     *  traffic on shard A does not block traffic on shard B. */
    private final ConcurrentMap<Long, ShardState> shards = new ConcurrentHashMap<>();

    /**
     * Mint an ID routed to {@code shardId}.
     * In a real Instagram-style system, this is invoked by the application
     * after computing the shard for the row (e.g. {@code userId % NUM_SHARDS}).
     */
    public long nextId(long shardId) {
        if (shardId < 0 || shardId > MAX_SHARD_ID) {
            throw new IllegalArgumentException("shardId must be 0.." + MAX_SHARD_ID);
        }
        ShardState state = shards.computeIfAbsent(shardId, k -> new ShardState());
        long now = state.nextTimestampAndSequence();
        long elapsed = (now >>> SEQUENCE_BITS) - EPOCH_MILLIS;
        long seq = now & MAX_SEQUENCE;
        if (elapsed < 0) {
            throw new IllegalStateException("Clock is before EPOCH_MILLIS");
        }

        return (elapsed << TIMESTAMP_SHIFT)
            | (shardId << SHARD_SHIFT)
            | seq;
    }

    public static Instant timestampOf(long id) {
        long ms = (id >>> TIMESTAMP_SHIFT) + EPOCH_MILLIS;
        return Instant.ofEpochMilli(ms);
    }

    public static long shardOf(long id) {
        return (id >>> SHARD_SHIFT) & MAX_SHARD_ID;
    }

    public static long sequenceOf(long id) {
        return id & MAX_SEQUENCE;
    }

    /**
     * Per-shard counter state. The sequence is per-(shard, ms); a wrap forces
     * us to wait for the next millisecond, matching what Postgres+nextval
     * achieves naturally in the real Instagram setup.
     */
    private static final class ShardState {
        private long lastTimestamp = -1L;
        private long sequence = 0L;

        /**
         * Returns a packed {@code (timestamp << SEQUENCE_BITS) | sequence}.
         * Synchronized per-shard so heavy traffic on one shard cannot
         * affect IDs minted on a different shard.
         */
        synchronized long nextTimestampAndSequence() {
            long now = System.currentTimeMillis();
            if (now < lastTimestamp) {
                throw new IllegalStateException(
                    "Clock moved backwards by " + (lastTimestamp - now) + " ms");
            }
            if (now == lastTimestamp) {
                sequence = (sequence + 1) & MAX_SEQUENCE;
                if (sequence == 0) {
                    // Sequence wrapped within the same ms — wait for the next.
                    now = waitNextMillis(lastTimestamp);
                }
            } else {
                sequence = 0L;
            }
            lastTimestamp = now;
            return (now << SEQUENCE_BITS) | sequence;
        }

        private static long waitNextMillis(long lastTs) {
            long now = System.currentTimeMillis();
            while (now <= lastTs) {
                now = System.currentTimeMillis();
            }
            return now;
        }
    }
}
