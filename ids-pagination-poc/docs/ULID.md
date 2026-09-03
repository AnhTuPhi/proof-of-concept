# ULID — when and why

> **One-line mental model:** *"I'm replacing a UUID and want it sortable."*

ULID (Universally Unique Lexicographically Sortable Identifier) is a 128-bit ID
encoded as a 26-character Crockford base32 string. The first 48 bits are a
millisecond timestamp; the remaining 80 bits are crypto-random.

Spec: <https://github.com/ulid/spec>

Reference implementation in this repo: `src/main/java/com/poc/ids/UlidGenerator.java`.

---

## Use ULID when…

### 1. The ID is a database primary key or sort key

ULIDs are lexicographically sortable, so `ORDER BY id` matches `ORDER BY created_at`
for free. That gives you:

- **B-tree-friendly inserts** — new rows append to the rightmost leaf of the
  index, no page splits, no fragmentation. Random UUIDs (v4) destroy this;
  ULIDs preserve it.
- **Range queries work on the ID alone** —
  `WHERE id BETWEEN ulid_for(yesterday) AND ulid_for(today)` without a separate
  `created_at` index.
- **Pagination via cursor on `id`** — exactly the pattern in this POC's
  `CursorPaginator`. With UUIDv4 you'd need a composite `(created_at, id)`
  cursor; with ULID you can cursor on `id` alone.

### 2. You're on a NoSQL store where the PK is a string

- **MongoDB `_id`** — default is `ObjectId`, but ULID is a drop-in upgrade with
  a real cross-language spec.
- **Cassandra / ScyllaDB partition keys** — ULIDs give time-clustered partitions
  (caveat: this can become a hot-spot pattern; sometimes you actually want the
  opposite, e.g. bucketed prefixes).
- **DynamoDB sort keys** — ULIDs let you do "newest N items" queries cheaply.
- **Redis sorted sets / streams** — ULID strings sort naturally.

### 3. You need to debug "when was this created?" from the ID

ULID's first 48 bits = millisecond timestamp. You can decode creation time from
the ID with no DB round trip:

```java
Instant created = UlidGenerator.timestampOf("01ARZ3NDEKTSV4RRFFQ69G5FAV");
```

Great for:

- Distributed tracing / correlation IDs
- Log analysis ("show me everything around 14:32 UTC")
- Audit trails
- Quick eyeballing during incident response

### 4. High-volume event/message systems

In all of these you want: unique, sortable, no central coordinator. ULID nails it.

- Kafka message keys
- Outbox-pattern event IDs
- Webhook delivery IDs
- Append-only audit logs

### Concrete examples

- `events.id` in an event-sourcing store
- `messages.id` in a chat app
- `orders.id` if you don't mind 16-byte PKs
- `_id` in any MongoDB collection
- Trace/span IDs in OpenTelemetry-style systems

---

## Do NOT use ULID when…

### The ID appears in a URL the user sees, types, or shares

ULID leaks the creation timestamp. An attacker who sees one ID can:

- Tell when it was created (timestamp is recoverable).
- Estimate how many you have (sequential timestamps reveal volume).
- In some setups, guess nearby IDs.

For public URLs, share links, invite codes, and anything security-sensitive,
use **NanoID** instead — see `NANOID.md`.

### You need IDs to fit in 8 bytes

ULID is 16 bytes binary / 26 chars text. If your tables are huge and 16-byte
PKs are a real cost concern, use **Snowflake** instead (8 bytes, but needs
per-process worker IDs — see `DEPLOYMENT.md`).

### You need a custom alphabet

ULID's alphabet is fixed by the spec (Crockford base32). For human-typed codes
where you'd want to strip ambiguous characters, gift-card-friendly alphabets,
or numeric-only IDs, use **NanoID**.

---

## How it compares

| Property              | ULID            | UUIDv4          | UUIDv7          | Snowflake       |
| --------------------- | --------------- | --------------- | --------------- | --------------- |
| Size                  | 128 bit / 26 ch | 128 bit / 36 ch | 128 bit / 36 ch | 64 bit          |
| Sortable by time      | ✅              | ❌              | ✅              | ✅              |
| Coordination needed   | ❌              | ❌              | ❌              | ✅ (worker IDs) |
| Decodable timestamp   | ✅              | ❌              | ✅              | ✅              |
| Standard / RFC        | spec on GitHub  | RFC 9562        | RFC 9562        | de facto only   |
| Random bits           | 80              | 122             | 74              | 0 (sequence)    |
| URL-safe              | ✅              | ✅              | ✅              | numeric only    |

ULID and UUIDv7 are very close — same idea, different encoding. UUIDv7 is the
newer standard; ULID has wider library support across older ecosystems. If
you're starting fresh in 2026, either is fine; consistency with your stack matters
more than the choice itself.

---

## The "use ULID for the PK, NanoID for the URL" pattern

Most production systems use **both** for different columns of the same table:

```sql
CREATE TABLE orders (
  id         CHAR(26)    PRIMARY KEY,        -- ULID: sortable, B-tree friendly
  public_id  VARCHAR(21) UNIQUE NOT NULL,    -- NanoID: appears in /orders/{public_id}
  created_at TIMESTAMP   NOT NULL,
  ...
);
```

- Internal joins, indexes, cursor pagination → use `id` (ULID).
- Public-facing URLs and APIs → use `public_id` (NanoID).
- No information leak in URLs, no B-tree fragmentation internally.

See `NANOID.md` for when the NanoID side of this pattern is the right call.
