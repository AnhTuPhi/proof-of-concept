# NanoID — when and why

> **One-line mental model:** *"I'm putting this in a URL and want it short + unguessable."*

NanoID is a configurable, URL-safe, crypto-random ID. Default: 21 characters
from a 64-symbol alphabet — same collision safety as UUID (~126 bits of entropy),
but ~40% shorter.

Spec: <https://github.com/ai/nanoid>

Reference implementation in this repo: `src/main/java/com/poc/ids/NanoIdGenerator.java`.

---

## Use NanoID when…

### 1. The ID appears in a URL the user sees, types, or shares

NanoID's win is **short + unguessable + URL-safe**. Compare:

- `https://app.com/d/V1StGXR8_Z5jdHi6B-myT` (NanoID, 21 chars)
- `https://app.com/d/01ARZ3NDEKTSV4RRFFQ69G5FAV` (ULID, 26 chars)
- `https://app.com/d/f47ac10b-58cc-4372-a567-0e02b2c3d479` (UUID, 36 chars)

The shorter URL is a real UX win for anything people share over chat, paste
into emails, or copy by hand.

### 2. You actively want IDs to be unguessable

NanoID is **pure crypto-random** — no timestamp, no structure. An attacker who
sees one ID cannot:

- Guess the next or previous one (no sequence).
- Tell when it was created (no timestamp leak).
- Estimate how many you have (no enumeration via timing or order).

ULID and Snowflake both leak all three. So for **anything security-sensitive**,
NanoID wins:

- "Anyone with the link" share tokens
- Password-reset / email-confirmation tokens (short-lived ones)
- API keys (often with a typed prefix: `sk_live_<nanoid>`)
- Invite codes
- Document slugs that should be private-by-obscurity

### 3. You need a custom alphabet

This is NanoID's underrated feature. The POC's constructor takes a custom alphabet:

```java
new NanoIdGenerator(random, customAlphabet, customSize)
```

Real uses:

- **Human-readable codes** — strip out `0/O/1/I/L` so users don't mistype:
  `"23456789ABCDEFGHJKMNPQRSTUVWXYZ"` (Crockford-style alphabet, ~5 bits/char).
  Good for coupon codes, gift cards, 2FA backup codes.
- **Lowercase-only** for case-insensitive systems (legacy DBs, some auth providers).
- **Numeric-only PINs** — high entropy by length (12-digit numeric = ~40 bits).
- **Profanity-safe alphabets** that avoid producing real English words.

ULID's alphabet is fixed by spec; you cannot change it.

### 4. Short codes where collision risk is acceptable

NanoID lets you trade entropy for length explicitly:

| Length | Entropy   | Safe for                                          |
| ------ | --------- | ------------------------------------------------- |
| 21 ch  | ~126 bits | Forever — UUID-equivalent                         |
| 12 ch  | ~71 bits  | Invite codes, millions/year                       |
| 8 ch   | ~47 bits  | URL shorteners, tens of thousands/year            |
| 6 ch   | ~36 bits  | One-time verification codes that expire quickly   |

You pick the trade-off. ULID's length is fixed at 26 characters.

Use the birthday-paradox formula to size your IDs:
`p_collision ≈ (n² / 2) / 2^bits`. For 1M IDs at 47 bits, that's
about 0.0036% — fine for short URLs, not fine for permanent PKs.

### Concrete examples

- `users.public_id` (the slug in `/u/{public_id}`) while keeping a private
  sortable PK
- `share_links.token`
- `invite_codes.code` (with a custom no-ambiguous-chars alphabet)
- `api_keys.key`
- Short URL slugs in a link shortener
- Document IDs in URLs (Notion-style: `Page-V1StGXR8_Z5jdHi6B`)
- 2FA recovery codes printed on paper

---

## Do NOT use NanoID when…

### It's a database primary key on a large table

NanoIDs are pure-random, which means they:

- **Fragment B-tree indexes** — every insert lands at a random spot in the index,
  causing page splits and bloat. Murder on MySQL/InnoDB.
- **Are not sortable** — no time order, so `ORDER BY id` is meaningless. You'll
  need a separate `created_at` index for any time-based query.
- **Don't support cursor pagination on `id` alone** — you'd need a composite
  cursor like `(created_at, id)` (the pattern in this POC's `CursorPaginator`).

For a sortable, B-tree-friendly PK, use **ULID** (see `ULID.md`) or **Snowflake**
(see `DEPLOYMENT.md`).

### You need to decode "when was this created?" from the ID

NanoID is pure entropy — no embedded metadata, ever. If you want to recover the
creation time from the ID itself (for debugging, logs, distributed tracing),
use **ULID** or **Snowflake**.

### You're using it as an event/message key in a streaming system

Kafka, append-only logs, event sourcing — these benefit from time-ordered IDs
so consumers can resume from a known position. NanoID's randomness fights this
pattern. Use **ULID** for event IDs.

---

## How it compares

| Property              | NanoID (default)  | UUIDv4          | ULID            | Snowflake       |
| --------------------- | ----------------- | --------------- | --------------- | --------------- |
| Size                  | 21 ch / ~126 bits | 36 ch / 122 bits| 26 ch / 128 bits| 64 bit          |
| Sortable by time      | ❌                | ❌              | ✅              | ✅              |
| Unguessable           | ✅                | ✅              | partial         | ❌              |
| Custom alphabet       | ✅                | ❌              | ❌              | ❌              |
| Decodable timestamp   | ❌                | ❌              | ✅              | ✅              |
| Coordination needed   | ❌                | ❌              | ❌              | ✅ (worker IDs) |
| URL-safe              | ✅ (by default)   | ✅              | ✅              | numeric only    |
| Tunable length        | ✅                | ❌              | ❌              | ❌              |

NanoID is essentially UUIDv4's better-packaged cousin: same randomness story,
shorter encoding, configurable to whatever your domain needs.

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

Stripe, Shopify, Notion, Linear, and most modern SaaS APIs do exactly this.
The IDs you see in URLs (`prod_abc123`, `inv_xyz789`) are the public NanoID-style
slug; the internal PKs are something boring and sortable.

See `ULID.md` for when the ULID side of this pattern is the right call.
