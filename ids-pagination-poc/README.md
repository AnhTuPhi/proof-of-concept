# IDs & Cursor Pagination — Java 21 POC

A small, dependency-free demo of two patterns commonly needed by distributed
systems:

1. **Distributed unique IDs** without a database sequence — seven schemes
   covering the major real-world variants.
2. **Composite cursor pagination** over `(created_at, id)` instead of
   `OFFSET / LIMIT` — avoids the deep-page performance cliff and stays stable
   under concurrent inserts.

Pure Java 21. No Spring, no DB driver, no external libs apart from JUnit for tests.

---

## ID strategies in this POC

### Coordination-free (no worker IDs to assign)

| Generator   | Size              | Sortable? | Best at                                          |
| ----------- | ----------------- | --------- | ------------------------------------------------ |
| **ULID**    | 128-bit, 26 chars | yes       | DB primary keys, NoSQL `_id`, event/log IDs      |
| **NanoID**  | 21 chars (tunable)| no        | Public URLs, share links, API keys, invite codes |

### Snowflake-family (need a unique `(worker)` per generator)

| Generator                 | Time bits      | Worker bits         | Sequence bits | Range  | Throughput          | Headline                                  |
| ------------------------- | -------------- | ------------------- | ------------- | ------ | ------------------- | ----------------------------------------- |
| **Twitter Snowflake**     | 41 @ 1ms       | 10 (5 dc + 5 w)     | 12            | 69 yr  | ~4M IDs/s/machine   | The original. Built for Twitter's fleet.  |
| **Sonyflake**             | 39 @ 10ms      | 16                  | 8             | 174 yr | ~25,600 IDs/s/m     | Bigger fleet, longer lifetime, less peak. |
| **Discord**               | 41 @ 1ms       | 10 (5 w + 5 p)      | 12            | 69 yr  | ~4M IDs/s/process   | Same as Twitter, 2015 epoch, w+p naming.  |
| **Instagram**             | 41 @ 1ms       | 13 (logical shard)  | 10            | 69 yr  | ~1M IDs/s/shard     | Embeds DB shard for routing-by-ID.        |
| **Apache ShardingSphere** | 41 @ 1ms       | 10                  | 12            | 69 yr  | ~4M IDs/s/machine   | + clock-back tolerance + vibration.       |

Deep-dive docs for each pattern live in [`docs/`](docs/).

---

## Layout

```
src/main/java/com/poc/
├── ids/
│   ├── SnowflakeIdGenerator.java         // Twitter, 41+10+12
│   ├── SonyflakeIdGenerator.java         // Sony, 39+8+16
│   ├── DiscordIdGenerator.java           // Snowflake w/ 2015 epoch, 5w+5p
│   ├── InstagramIdGenerator.java         // 41+13+10, per-shard sequence
│   ├── ShardingSphereIdGenerator.java    // Snowflake + clock-back tolerance + vibration
│   ├── UlidGenerator.java                // 128-bit, lex-sortable
│   └── NanoIdGenerator.java              // configurable URL-safe
├── pagination/
│   ├── Cursor.java                       // (createdAt, id) as opaque base64 token
│   ├── CursorPage.java                   // result envelope: items + nextCursor + hasMore
│   └── CursorPaginator.java              // in-memory walker, mirrors the SQL pattern
├── model/Item.java
└── demo/DemoRunner.java                  // runnable showcase of all eight sections

docs/
├── EXPLAIN.md             // Snowflake / ULID / NanoID — pros & cons at a glance
├── SNOWFLAKE.md           // Snowflake on Kubernetes — what actually fits
├── SNOWFLAKE_VARIANTS.md  // Sonyflake, Discord, Instagram, ShardingSphere — bit-by-bit
├── ULID.md                // ULID deep dive — when and why
└── NANOID.md              // NanoID deep dive — when and why
```

---

## Run it

```bash
mvn -q test                     # unit tests (42 tests across IDs + pagination)
mvn -q compile exec:java        # run DemoRunner
```

No Maven on the box? `javac` and `java` are enough:

```bash
mkdir -p target/classes
javac -d target/classes $(find src/main/java -name '*.java')
java -cp target/classes com.poc.demo.DemoRunner
```

Expected output (abbreviated):

```
== 1. Snowflake (Twitter) — 64-bit, time-sortable - 69-year range ==
  id=…  ts=2026-…  dc=1  worker=1  seq=0
  → monotonic ✓  ~4M IDs/sec/node

== 4. Sonyflake — 174-year range, up to 65,536 machines ==
  id=…  ts=2026-…  machine=12345  seq=0
  → 10ms ticks, 256 IDs / 10ms / machine, 174-yr range

== 6. Instagram — sharded snowflake (per-shard sequence) ==
  id=…  ts=2026-…  shard=5  seq=0
  → ID encodes its shard → routing without metadata lookup

== 8. Composite cursor pagination — (createdAt, id) ==
  page 1 (5 items, hasMore=true)
    …
    nextCursor=MTc2NzIyNTYwMjAwMDozMTkxODIxNjU4NDc5NzM4OTE
  → walked 13 rows across 3 pages, no duplicates, no skips ✓
```

---

## Why composite cursors?

`SELECT … OFFSET 100000 LIMIT 20` makes the database scan and throw away
100,000 rows on every request — page latency grows linearly with depth.
Worse, the same row can appear on two consecutive pages (or get skipped) if
something inserts while the user is scrolling.

A cursor over `(created_at, id)` rides an existing index:

```sql
SELECT *
  FROM items
 WHERE (created_at, id) > (:lastCreatedAt, :lastId)
 ORDER BY created_at ASC, id ASC
 LIMIT :pageSize;
```

- O(log n) seek + O(pageSize) scan, regardless of how deep you are.
- Tiebreaker on `id` keeps order deterministic when two rows share a timestamp.
- The cursor is opaque to the client (base64), so the server can change the
  underlying schema without breaking pagination contracts.

Same pattern GitHub, Slack, Twitter, Linear, and most modern APIs use for
feed-style endpoints.

---

## When to pick which ID

A practical decision flow:

```
Will the ID appear in a URL or be shown to users?
├── Yes
│   ├── Must be unguessable / non-enumerable?  → NanoID
│   └── User-typed (gift code, 2FA)?           → NanoID with custom alphabet
└── No (internal: DB PK, event ID, trace ID)
    ├── 8-byte BIGINT and < 1024 machines?     → Snowflake (or one of its variants)
    ├── > 1024 machines or > 69 years?         → Sonyflake
    ├── Sharded DB, ID should encode shard?    → Instagram pattern
    ├── Heavy production use w/ id % N hash?   → ShardingSphere variant
    └── Want zero coordination?                → ULID (or UUIDv7)
```

A common production combo:

```sql
CREATE TABLE orders (
  id         BIGINT      PRIMARY KEY,        -- Snowflake (internal joins, indexes)
  public_id  VARCHAR(21) UNIQUE NOT NULL,    -- NanoID (appears in /orders/{public_id})
  ...
);
```

Two IDs per row: a fast sortable PK internally, an unguessable short slug in
URLs.

### Quick reference

| Need                                                  | Pick                                |
| ----------------------------------------------------- | ----------------------------------- |
| 8-byte BIGINT PK, single small fleet                  | Snowflake                           |
| 8-byte BIGINT PK, very large fleet or long-lived      | Sonyflake                           |
| 8-byte BIGINT PK, sharded by user/tenant              | Instagram                           |
| 8-byte BIGINT PK, hardened against NTP / hot-spotting | ShardingSphere                      |
| String PK, sortable, no coordination                  | ULID                                |
| Short, random, URL-safe, unguessable                  | NanoID                              |
| Cross-DB unique without coordination                  | ULID, NanoID, or any Snowflake var. |

Snowflake-family generators all need a unique `(datacenter, worker)` or `shard`
per generator instance. See [`docs/SNOWFLAKE.md`](docs/SNOWFLAKE.md) for how to
assign those in Kubernetes without collisions.

---

## Tests

42 tests across 7 generators and the cursor paginator. Each generator covers:

- Uniqueness (10k–100k IDs in a tight loop).
- Monotonicity within a single generator.
- Round-trip decoding (timestamp / machine / shard / sequence).
- Multi-thread safety where applicable.
- Bounds checking on constructor arguments.
- Pattern-specific behaviors (e.g. ShardingSphere's vibration).

The cursor paginator additionally verifies it walks every row exactly once
with no duplicates or skips, including the `(createdAt, id)` tiebreaker case.
