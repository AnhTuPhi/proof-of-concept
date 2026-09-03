# Snowflake / ULID / NanoID — when to use which

## Snowflake ID (64-bit integer)

**Layout:** `[1 sign | 41 ts | 5 dc | 5 worker | 12 seq]` → ~69 years, 32 dc × 32 workers, 4096 IDs/ms/node.

### Why use it
- **Fits in `BIGINT`** — half the storage of a UUID, smaller indexes, faster joins.
- **Time-sortable** — `ORDER BY id` ≈ `ORDER BY created_at`, no extra index needed.
- **Embeds metadata** — you can decode "which datacenter / worker / when" from the ID alone, handy for debugging and sharding.
- **No DB round trip** — generated in-process, so write throughput isn't bottlenecked by `SELECT nextval()`.

### Pros
- Compact (8 bytes vs 16 for UUID).
- Naturally clustered B-tree inserts → no page splits, no index fragmentation (huge win for MySQL/InnoDB primary keys).
- High throughput: 4096 IDs/ms/worker = ~4M/sec/node.

### Cons
- **Needs unique `(dc, worker)` per generator instance** — must be coordinated (ZooKeeper, etcd, K8s pod ordinal, config). Misconfigured duplicates = silent ID collisions.
- **Clock-sensitive** — if NTP rolls the clock backward, generator must refuse to mint IDs or wait. Production code needs monitoring for this.
- **Enumerable** — sequential IDs leak business info ("how many orders did they have yesterday?"). Don't expose raw Snowflake IDs in public URLs.
- Not a standard — every company has its own variant (Twitter, Discord, Instagram all differ slightly).

### Use when
- High-write OLTP systems (orders, events, messages).
- Internal IDs that won't appear in URLs.
- You already control deployment topology (can assign worker IDs).

---

## ULID (128-bit, 26-char string)

**Layout:** `[48 bits timestamp ms | 80 bits randomness]` → encoded in Crockford base32.

### Why use it
- **Drop-in UUID replacement that's also sortable** — same 128-bit space, same collision odds, but `ORDER BY id` works.
- **String-friendly** — Crockford base32 has no ambiguous chars (no `0/O`, no `1/I/L`), case-insensitive, URL-safe.
- **No coordination needed** — pure timestamp + crypto random, every node generates independently.

### Pros
- Self-contained: no worker IDs, no central service.
- Sortable in databases that store as text (good for Mongo `_id`, Cassandra partition keys, Redis sorted sets).
- Shorter than UUID when printed (26 chars vs 36).
- Spec'd and stable across languages: same format in Node, Rust, Python, Go, Java.

### Cons
- **String storage cost** — 26 bytes vs 8 bytes for Snowflake. Bigger indexes, more memory pressure.
- **80 bits of randomness only** — still astronomically safe, but lower than UUIDv4's 122 random bits.
- **Monotonicity within the same ms** requires bookkeeping (this POC implements it by incrementing the last random value). Multi-node monotonicity is impossible by design.
- Slower to generate than Snowflake (SecureRandom + base32 encode).

### Use when
- You want UUID semantics (no coordination, globally unique) **but** also want time-ordered IDs.
- Document stores / NoSQL where the primary key is a string anyway.
- Distributed systems where you can't assign worker IDs cleanly.

---

## NanoID (configurable length, default 21 chars)

**Layout:** N characters from a custom alphabet (default: 64 URL-safe symbols). No timestamp, no structure.

### Why use it
- **Compact + URL-safe** — 21 chars carries ~126 bits of entropy, comparable to UUID, but ~40% shorter.
- **Customizable** — pick your own alphabet (e.g. exclude lookalikes for human entry, or use lowercase-only for case-insensitive systems).
- **Simple** — no clocks, no worker IDs, no monotonicity rules.

### Pros
- Smallest collision-safe string ID (21 chars vs 36 for UUID vs 26 for ULID).
- Great for public-facing IDs: short URLs, share codes, invite tokens, document slugs.
- Crypto-random by default → unguessable, safe against enumeration attacks.
- Tunable: shrink to 10 chars for short codes (with higher collision risk), or grow to 32 for paranoia.

### Cons
- **Not sortable** — totally random, so DB inserts hit random index pages → fragmentation on B-trees (bad for primary keys on big tables).
- **No embedded metadata** — can't recover the creation time or origin from the ID.
- **Collision probability depends on size** — at 21 chars you're fine forever, at 10 chars you'll collide eventually. Has to be sized to your write rate.
- Not a standard — though widely adopted, the spec is "whatever the reference JS lib does."

### Use when
- Public URLs / share links / API keys / short codes.
- IDs the user sees and might type.
- You **don't** want them sortable or guessable (anti-enumeration).
- Secondary IDs alongside a real Snowflake/ULID primary key.

---

## Decision matrix

| Question | Snowflake | ULID | NanoID |
|---|---|---|---|
| Sortable by time? | yes | yes | no |
| Fits in `BIGINT` (8 bytes)? | yes | no (16) | no (varies) |
| Coordination needed? | yes (worker IDs) | no | no |
| URL-safe string? | numeric only | yes | yes |
| Unguessable / non-enumerable? | no | partially | yes |
| Best for | DB primary keys, high-write OLTP | distributed `_id` fields | public URLs, share codes |
| Worst for | public URLs, multi-cluster setups | tight storage, raw int joins | DB primary keys on huge tables |

## Common production combo

Many teams use **two IDs per row**:

- `id BIGINT` (Snowflake) — internal primary key, joins, indexes.
- `public_id VARCHAR(21)` (NanoID) — what appears in `/orders/{public_id}` URLs.

You get the storage/sort benefits of Snowflake internally **and** non-enumerable, short URLs externally — without the downsides of either alone. ULID slots into either role when you can't coordinate worker IDs but still want time ordering.