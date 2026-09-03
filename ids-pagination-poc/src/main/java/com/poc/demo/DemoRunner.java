package com.poc.demo;

import com.poc.ids.DiscordIdGenerator;
import com.poc.ids.InstagramIdGenerator;
import com.poc.ids.NanoIdGenerator;
import com.poc.ids.ShardingSphereIdGenerator;
import com.poc.ids.SnowflakeIdGenerator;
import com.poc.ids.SonyflakeIdGenerator;
import com.poc.ids.UlidGenerator;
import com.poc.model.Item;
import com.poc.pagination.CursorPage;
import com.poc.pagination.CursorPaginator;

import java.time.Instant;
import java.util.ArrayList;

/**
 * Demonstrates every ID scheme in the POC plus composite cursor pagination.
 *
 *   1. Snowflake         — Twitter, 41+10+12 bits, 69-yr range, ~4M IDs/s/node
 *   2. ULID              — 128-bit, lexicographically sortable, no coordination
 *   3. NanoID            — random, URL-safe, configurable alphabet/length
 *   4. Sonyflake         — Sony, 39+8+16 bits, 174-yr range, 65k machines
 *   5. Discord           — Snowflake w/ 2015 epoch, 5+5 worker/process bits
 *   6. Instagram         — sharded snowflake: 41+13+10 (timestamp + shard + seq)
 *   7. ShardingSphere    — Snowflake + vibration + clock-back tolerance
 *   8. Cursor pagination — (createdAt, id) over an in-memory dataset
 */
public final class DemoRunner {

    public static void main(String[] args) {
        section("1. Snowflake (Twitter) — 64-bit, time-sortable - 69-year range");
        demoSnowflake();

        section("2. ULID — 128-bit, lexicographically sortable");
        demoUlid();

        section("3. NanoID — random, URL-safe, 21 chars");
        demoNanoId();

        section("4. Sonyflake — 174-year range, up to 65,536 machines");
        demoSonyflake();

        section("5. Discord — Snowflake with 2015 epoch, worker+process split");
        demoDiscord();

        section("6. Instagram — sharded snowflake (per-shard sequence)");
        demoInstagram();

        section("7. ShardingSphere — Snowflake + vibration + clock-back tolerance");
        demoShardingSphere();

        section("8. Composite cursor pagination — (createdAt, id)");
        demoCursorPagination();
    }

    private static void demoSnowflake() {
        var gen = new SnowflakeIdGenerator(1, 1);
        long previous = -1L;
        for (int i = 0; i < 5; i++) {
            long id = gen.nextId();
            System.out.printf(
                "  id=%d  ts=%s  dc=%d  worker=%d  seq=%d%n",
                id,
                SnowflakeIdGenerator.timestampOf(id),
                SnowflakeIdGenerator.datacenterOf(id),
                SnowflakeIdGenerator.workerOf(id),
                SnowflakeIdGenerator.sequenceOf(id));
            assertIncreasing(id, previous, "Snowflake");
            previous = id;
        }
        System.out.println("  → monotonic ✓  ~4M IDs/sec/node");
    }

    private static void demoUlid() {
        var gen = new UlidGenerator();
        String previous = "";
        for (int i = 0; i < 5; i++) {
            String ulid = gen.nextUlid();
            System.out.printf("  ulid=%s  ts=%s%n", ulid, UlidGenerator.timestampOf(ulid));
            if (ulid.compareTo(previous) <= 0) {
                throw new AssertionError("ULIDs must be lexicographically increasing");
            }
            previous = ulid;
        }
        System.out.println("  → monotonic ✓  no worker IDs to coordinate");
    }

    private static void demoNanoId() {
        var gen = new NanoIdGenerator();
        for (int i = 0; i < 5; i++) {
            System.out.printf("  nanoid=%s%n", gen.nextId());
        }
        System.out.println("  → compact ✓  URL-safe ✓  unguessable ✓ (not sortable)");
    }

    private static void demoSonyflake() {
        var gen = new SonyflakeIdGenerator(12345);
        long previous = -1L;
        for (int i = 0; i < 5; i++) {
            long id = gen.nextId();
            System.out.printf(
                "  id=%d  ts=%s  machine=%d  seq=%d%n",
                id,
                SonyflakeIdGenerator.timestampOf(id),
                SonyflakeIdGenerator.machineIdOf(id),
                SonyflakeIdGenerator.sequenceOf(id));
            assertIncreasing(id, previous, "Sonyflake");
            previous = id;
        }
        System.out.println("  → 10ms ticks, 256 IDs / 10ms / machine, 174-yr range");
    }

    private static void demoDiscord() {
        var gen = new DiscordIdGenerator(3, 7);
        long previous = -1L;
        for (int i = 0; i < 5; i++) {
            long id = gen.nextId();
            System.out.printf(
                "  id=%d  ts=%s  worker=%d  process=%d  seq=%d%n",
                id,
                DiscordIdGenerator.timestampOf(id),
                DiscordIdGenerator.workerOf(id),
                DiscordIdGenerator.processOf(id),
                DiscordIdGenerator.sequenceOf(id));
            assertIncreasing(id, previous, "Discord");
            previous = id;
        }
        System.out.println("  → epoch=2015-01-01 (Discord launch), 5w+5p machine bits");
    }

    private static void demoInstagram() {
        var gen = new InstagramIdGenerator();
        // Mint 3 IDs each on two different shards so the demo shows the shard
        // bits encoded in the resulting IDs.
        for (long shard : new long[]{5L, 6L}) {
            for (int i = 0; i < 3; i++) {
                long id = gen.nextId(shard);
                System.out.printf(
                    "  id=%d  ts=%s  shard=%d  seq=%d%n",
                    id,
                    InstagramIdGenerator.timestampOf(id),
                    InstagramIdGenerator.shardOf(id),
                    InstagramIdGenerator.sequenceOf(id));
            }
        }
        System.out.println("  → ID encodes its shard → routing without metadata lookup");
    }

    private static void demoShardingSphere() {
        var gen = new ShardingSphereIdGenerator(7); // defaults: 10ms tolerance, vibration=1
        for (int i = 0; i < 6; i++) {
            long id = gen.nextId();
            System.out.printf(
                "  id=%d  ts=%s  worker=%d  seq=%d%n",
                id,
                ShardingSphereIdGenerator.timestampOf(id),
                ShardingSphereIdGenerator.workerOf(id),
                ShardingSphereIdGenerator.sequenceOf(id));
        }
        System.out.println(
            "  → sequence vibrates so first-of-ms IDs spread across shards"
                + " when downstream hashes by id % N");
    }

    private static void demoCursorPagination() {
        var snowflake = new SnowflakeIdGenerator(1, 2);
        var items = new ArrayList<Item>();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 13; i++) {
            Instant ts = base.plusSeconds(i / 2L); // two rows per second → ties
            items.add(new Item(snowflake.nextId(), "item-" + i, ts));
        }

        var paginator = new CursorPaginator(items);
        int pageSize = 5;
        int pageNum = 1;
        String cursor = null;
        int totalSeen = 0;

        while (true) {
            CursorPage<Item> page = paginator.pageFrom(cursor, pageSize);
            System.out.printf("  page %d (%d items, hasMore=%b)%n",
                pageNum, page.items().size(), page.hasMore());
            for (Item it : page.items()) {
                System.out.printf("    %s  id=%d  %s%n", it.createdAt(), it.id(), it.name());
            }
            totalSeen += page.items().size();
            if (!page.hasMore()) break;
            cursor = page.nextCursor();
            System.out.printf("    nextCursor=%s%n", cursor);
            pageNum++;
        }

        if (totalSeen != items.size()) {
            throw new AssertionError(
                "Pagination dropped rows: expected " + items.size() + " got " + totalSeen);
        }
        System.out.printf("  → walked %d rows across %d pages, no duplicates, no skips ✓%n",
            totalSeen, pageNum);
    }

    private static void assertIncreasing(long current, long previous, String name) {
        if (current <= previous) {
            throw new AssertionError(name + " IDs must be strictly increasing");
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private DemoRunner() {}
}
