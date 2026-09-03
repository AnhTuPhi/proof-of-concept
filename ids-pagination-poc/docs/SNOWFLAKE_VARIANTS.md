# Snowflake variants — what changed and why

Twitter's 2010 Snowflake is the baseline. Every "Snowflake-like" scheme that
came after redistributes its 64 bits to fix something the original didn't
handle well for that team's workload. Four notable variants live in this POC:

| Generator                 | Time bits | Worker bits     | Sequence bits | Range  | Throughput          | Why it exists                            |
| ------------------------- | --------- | --------------- | ------------- | ------ | ------------------- | ---------------------------------------- |
| Twitter Snowflake         | 41 (1ms)  | 10 (5 dc + 5 w) | 12            | 69 yr  | ~4M IDs/s/machine   | The original. Built for Twitter's fleet. |
| Sonyflake                 | 39 (10ms) | 16              | 8             | 174 yr | ~25,600 IDs/s/m     | Bigger fleet, longer lifetime, less peak |
| Discord                   | 41 (1ms)  | 10 (5 w + 5 p)  | 12            | 69 yr  | ~4M IDs/s/process   | Same shape, 2015 epoch, worker/process   |
| Instagram                 | 41 (1ms)  | 13 (shard)      | 10            | 69 yr  | ~1M IDs/s/shard     | Embed the DB shard, source seq from DB   |
| Apache ShardingSphere     | 41 (1ms)  | 10              | 12            | 69 yr  | ~4M IDs/s/machine   | + clock-back tolerance, + vibration      |

All implementations live in `src/main/java/com/poc/ids/`.

---

## Sonyflake — long-lived, many small machines

**Repo:** <https://github.com/sony/sonyflake>
**Class:** `SonyflakeIdGenerator`

Sonyflake rebalances the 64 bits:

- Ticks every **10 ms** instead of every 1 ms → 39 bits buy ~174 years.
- **16 machine bits** = 65,536 generator instances per cluster.
- Only **8 sequence bits** = 256 IDs per 10ms per machine ≈ 25,600/sec/machine.

You're trading peak per-process throughput for fleet size and longevity.

```java
var gen = new SonyflakeIdGenerator(/* machineId */ 12345);
long id = gen.nextId();
Instant when    = SonyflakeIdGenerator.timestampOf(id);
long machine    = SonyflakeIdGenerator.machineIdOf(id);
long sequence   = SonyflakeIdGenerator.sequenceOf(id);
```

**Use when:** you run many small services for a long time, no single one needs
millions of IDs per second.

**Avoid when:** one hot service mints a firehose of IDs — you'll saturate the
256 / 10ms ceiling.

---

## Discord — same shape, different epoch

**Spec:** <https://discord.com/developers/docs/reference#snowflakes>
**Class:** `DiscordIdGenerator`

Bit layout is identical to Twitter Snowflake. The differences are practical
rather than structural:

- **Custom epoch:** 2015-01-01T00:00:00Z. The 41 timestamp bits encode
  "ms since Discord existed," giving the same 69-year range but starting
  from launch.
- **Machine bits split as 5 worker + 5 process** (not 5 datacenter + 5 worker).
  Discord runs many processes per host; the split reflects that topology.
- **Real Discord IDs are user-visible** (you can read them from any message
  URL) and the timestamp is intentionally recoverable for client-side ordering.

```java
var gen = new DiscordIdGenerator(/* workerId */ 3, /* processId */ 7);
long id = gen.nextId();
Instant when = DiscordIdGenerator.timestampOf(id);  // wall-clock time
```

**Takeaway:** Discord didn't invent a new ID scheme — they branded an existing
one with their own epoch and naming convention. The same lesson applies to
any team: pick your own epoch when you adopt Snowflake, so the timestamp bits
encode "time since this product existed" instead of wasting 45 years on dates
that predate your company.

---

## Instagram — sharded snowflake (the ID knows its shard)

**Reference:** <https://instagram-engineering.com/sharding-ids-at-instagram-1cf5a71e5a5c>
**Class:** `InstagramIdGenerator`

This is the most interesting variant because it changes **where the ID comes from**:

- 41 bits timestamp (1 ms).
- **13 bits = logical shard ID** (0..8191) — encodes which database shard owns this row.
- **10 bits = per-shard sequence** (0..1023, wraps).

The sequence is **per shard**, sourced from a database sequence in the real
Instagram design:

```sql
-- inside each shard schema
CREATE SEQUENCE table_id_seq;

CREATE FUNCTION next_id(OUT result bigint) AS $$
DECLARE
    our_epoch bigint := 1314220021721;  -- Instagram's epoch
    seq_id bigint;
    now_millis bigint;
    shard_id int := 5;                  -- this shard's ID
BEGIN
    SELECT nextval('table_id_seq') %% 1024 INTO seq_id;
    SELECT FLOOR(EXTRACT(EPOCH FROM clock_timestamp()) * 1000) INTO now_millis;
    result := (now_millis - our_epoch) << 23;
    result := result | (shard_id << 10);
    result := result | (seq_id);
END;
$$ LANGUAGE PLPGSQL;
```

The application picks a shard for the write (e.g. `user_id % NUM_SHARDS`) and
asks the database on that shard to mint the ID. **The shard is baked into the
ID itself**, so any later lookup can route to the right database with no
metadata table.

```java
var gen = new InstagramIdGenerator();
long userId = 42L;
long shard = userId % 4096;             // application-level sharding
long id    = gen.nextId(shard);
long shardFromId = InstagramIdGenerator.shardOf(id);  // routing without lookup
```

In this POC the per-shard sequence is an in-memory `AtomicLong` keyed by shard
— in production you'd replace `nextSequenceForShard` with a call to your
database. The ID structure is the durable contract.

**Use when:** your data is already sharded by a deterministic key (user ID,
tenant ID) and you want routing decisions to be local to the ID.

**Avoid when:** you don't have sharding yet — adding it just for ID generation
is over-engineered.

---

## Apache ShardingSphere — Snowflake hardened for production

**Docs:** <https://shardingsphere.apache.org/document/current/en/user-manual/common-config/builtin-algorithm/keygen/>
**Class:** `ShardingSphereIdGenerator`

ShardingSphere's `SNOWFLAKE` keygen keeps the Twitter bit layout but adds two
operational features that real teams care about:

### 1. Clock-back tolerance

Vanilla Snowflake throws an exception the instant the wall clock rewinds:

```java
if (now < lastTimestamp) {
    throw new IllegalStateException("Clock moved backwards by …");
}
```

That's correct but brittle — NTP corrections, VM pauses, container scheduling
hiccups, and leap-second adjustments all move the clock around by a few ms.
Production restarts every time this happens.

ShardingSphere's tweak: if the rewind is **smaller than `maxTolerateClockBackMillis`**
(default 10 ms), sleep until the clock catches up and continue. Beyond the
tolerance, throw.

```java
var gen = new ShardingSphereIdGenerator(
    /* workerId */ 7,
    /* maxTolerateClockBackMillis */ 10L,
    /* maxVibrationOffset */ 1);
```

### 2. Sequence vibration

When the sequence overflows within a single ms, vanilla Snowflake resets the
next-ms sequence to 0. That means the **first ID of every ms always ends in
`...000`** in the low 12 bits.

If downstream code shards rows by `id % N` (very common when distributing
writes across partitions), every ms's first ID lands on the same shard —
a steady, predictable hot spot.

ShardingSphere fixes this by starting each ms at a small randomized offset
in `[0, maxVibrationOffset]`. The sequence still increments normally; it just
doesn't always start at zero. Result: writes spread evenly across downstream
shards.

The trade-off: each unit of vibration costs one slot of per-ms sequence
headroom, so don't crank it up to 4095 — usually 1–7 is plenty.

**Use when:** you run Snowflake long enough in production that NTP
adjustments and `id % N` hashing start mattering. Most teams hit both within
a year.

---

## Decision flow

```
Will you run on a small, stable fleet (≤ 1024 workers, ≤ 69 yr)?
├── Yes → Twitter Snowflake (or ShardingSphere if you want clock-back safety)
└── No
    ├── Need > 1024 workers or > 69 yr? → Sonyflake
    ├── Already sharding your DB by hash? → Instagram pattern
    └── Want to drop coordination entirely? → ULID / UUIDv7 (see ULID.md)

Embedding worker/process info in the ID for debugging?
└── Discord-style worker+process split — same as Snowflake, just renamed

Sharding writes by id % N downstream?
└── Use ShardingSphere variant for vibration
```

## Try it

The DemoRunner exercises all four variants alongside the original Snowflake,
ULID, and NanoID:

```bash
javac -d target/classes $(find src/main/java -name '*.java')
java -cp target/classes com.poc.demo.DemoRunner
```

Tests cover uniqueness, monotonicity, multi-thread safety, and the vibration
behavior of the ShardingSphere generator.
