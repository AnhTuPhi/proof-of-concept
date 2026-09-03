# TECHNICAL.md — How each POC works

This document goes POC-by-POC. For each one:

1. **The hard problem** — the technically gnarly part.
2. **What we are protecting** — the asset / invariant.
3. **Solution shape** — the approach in a paragraph.
4. **Key tech by responsibility** — which piece does what.
5. **How it solves each sub-problem** — the sub-problems mapped to mechanisms.
6. **Tech debt to acknowledge** — what's intentionally faked or deferred.

The business framing lives in [ISSUE.md](ISSUE.md); scale-out correctness lives in
[CONSISTENCY.md](CONSISTENCY.md).

---

## 28 · Account Merge (`account-merge-poc`, port 8081)

### The hard problem
Reassign one person's data — spread across `users`, `auth_methods`, `trade_orders`,
`watchlists`, `audit_events` — from a duplicate account onto the surviving account,
**atomically**, **without hard-deleting anything**, while keeping *both* original login
methods working forever and recording who won each conflicting field.

### What we are protecting
Financial records (trade orders), the audit trail, and the invariant *every auth method a
real person owns resolves to exactly one live account*.

### Solution shape
A **soft merge with a redirect tombstone**. Instead of deleting the loser, we:
1. Preview conflicts (email / display name / phone) and row counts.
2. Apply the operator's chosen winning values to the **target** (survivor).
3. Bulk-`UPDATE user_id` on every FK table from source → target inside **one transaction**.
4. Convert the source user to status `MERGED_REDIRECT` with `mergedIntoUserId = target`.
   It is never deleted.
5. Login resolution **follows the redirect chain** so the Google login (still attached to
   the old row) transparently lands on the survivor.

### Key tech by responsibility

| Responsibility | Mechanism |
|----------------|-----------|
| Atomic multi-table move | `@Transactional` around all reassignments + status change ([AccountMergeService.merge](account-merge-poc/src/main/java/com/vndirect/poc/merge/service/AccountMergeService.java:62)) |
| Bulk row reassignment | JPQL `@Modifying @Query("update … set userId = :target where userId = :source")` per repo ([AuthMethodRepository:17](account-merge-poc/src/main/java/com/vndirect/poc/merge/repo/AuthMethodRepository.java:17)) |
| Never-delete guarantee | `UserStatus.MERGED_REDIRECT` tombstone + `mergedIntoUserId` pointer on `User` |
| Login keeps working | `resolveLogin` → `followRedirect` walks the chain to the survivor ([AccountMergeService:94](account-merge-poc/src/main/java/com/vndirect/poc/merge/service/AccountMergeService.java:94)) |
| Conflict decision capture | `MergeRequest` carries `winningEmail/DisplayName/Phone`; `applyConflictResolution` counts changes |
| Forensics | Two `AuditEvent` rows: `ACCOUNT_MERGED` on survivor, `ACCOUNT_MERGED_INTO` on loser |
| Duplicate-login safety | `@UniqueConstraint(provider, externalId)` on `auth_methods` |

### How it solves each sub-problem
- **Data spread across N tables** → one `@Transactional` method issues a bulk `UPDATE` per
  table; all-or-nothing.
- **No hard delete** → tombstone status + redirect pointer; loser row stays for audit and
  for its still-valid auth methods.
- **Both logins must work** → auth methods are *reassigned* to the survivor, and even if
  something still pointed at the old row, `followRedirect` (max 8 hops, cycle-guarded)
  resolves it.
- **Field conflicts** → previewed first, resolved explicitly by the caller, applied to the
  target, and counted in the `MergeReport`.
- **Retry safety** → the whole thing is a single transaction; a failure rolls back the
  partial move rather than stranding orders.

### Tech debt to acknowledge
- **In-memory H2** (`create-drop`) stands in for Oracle. Real deployment uses the shared
  Oracle instance and must consider lock/isolation behaviour under concurrent merges.
- **No pessimistic lock** on the two users during merge — two operators merging overlapping
  accounts simultaneously could interleave. Production needs `SELECT … FOR UPDATE` on both
  user rows (see [CONSISTENCY.md](CONSISTENCY.md)).
- **Redirect chains can grow** (A→B, then B→C). We cap traversal at 8 hops; a background
  job should *flatten* chains to a single hop.
- **Bulk JPQL updates bypass the persistence context** — fine here because we don't re-read
  those entities in the same unit of work, but a footgun if the flow grows.
- The conflict set is hardcoded to three scalar fields; a real merge has dozens plus
  collection-level dedupe (e.g. merging two "Favorites" watchlists).

---

## 29 · Multi-Device Sessions (`session-management-poc`, port 8082)

### The hard problem
Make **stateless JWT access tokens instantly revocable** — globally on password change and
individually per device — while rotating long-lived refresh tokens safely and detecting
their theft. Statelessness (speed) vs. instant revocation (safety) is the core tension.

### What we are protecting
The ability to *revoke trust immediately* on a stolen/shared device, and to catch
refresh-token reuse instead of silently minting tokens for a thief.

### Solution shape
A **hybrid stateless/stateful** design:
- **Access token = JWT** (60s TTL) carrying `sub`, `sid` (session id), `tv` (token version),
  `typ`. Validation is signature + expiry (stateless) **plus** two cheap server-side checks:
  session not revoked, and `tv` still matches the user's current token version.
- **Token version** is a per-user counter. Bumping it (on "logout everywhere" / password
  change) instantly invalidates *every* JWT and refresh token ever issued to that user —
  they carry a now-stale `tv`.
- **Per-device session** rows let you revoke exactly one device.
- **Refresh-token rotation**: each refresh is single-use; using it mints a new pair and
  records the new JTI on the session. Presenting an *old* (already-rotated) refresh JTI is
  treated as theft → the session is revoked.

### Key tech by responsibility

| Responsibility | Mechanism |
|----------------|-----------|
| Sign / parse tokens | `JwtService` (jjwt 0.12.6, HMAC-SHA256, shared secret) |
| Instant global revoke | `tv` claim vs. `UserAccount.tokenVersion`; `bumpTokenVersion` on logout-everywhere ([UserAccount:26](session-management-poc/src/main/java/com/vndirect/poc/session/domain/UserAccount.java:26)) |
| Per-device revoke | `Session.revoke()`; checked in `validateAccess` ([SessionService:62](session-management-poc/src/main/java/com/vndirect/poc/session/service/SessionService.java:62)) |
| Refresh rotation | `refresh()` swaps `refreshTokenJti` each use ([SessionService:84](session-management-poc/src/main/java/com/vndirect/poc/session/service/SessionService.java:84)) |
| Theft detection | Old JTI presented ≠ session's current JTI → `revoke()` + error |
| Device inventory | `listSessions(userId)` powers the "your devices" screen |

### How it solves each sub-problem
- **Stateless but revocable** → JWT gives fast, DB-free signature/expiry checks; the `tv`
  and `sid` lookups add O(1) map reads that turn it revocable without a full session read per
  claim.
- **Logout everywhere = now** → one `tokenVersion` increment; every outstanding token's `tv`
  is instantly stale on its next use.
- **Logout one device** → revoke that `sid`; other sessions untouched.
- **Refresh theft** → single-use JTIs; replay of a rotated token is unambiguous evidence and
  kills the session, containing the blast radius.
- **Short access TTL (60s)** → even in the tiny window before a revoke check matters, exposure
  is bounded; refresh (900s) rotates continuously.

### Tech debt to acknowledge
- **All state is in-memory** (`ConcurrentHashMap` for users, sessions, token versions). This
  is the single biggest thing to fix for scale-out: multiple pods each hold their own
  version of the truth, so a revoke on one pod is invisible to the others. Move to Redis
  (see [CONSISTENCY.md](CONSISTENCY.md)).
- **Toy password hash** — `Integer.toHexString(hashCode())`. Real code uses BCrypt/Argon2
  (flagged in the source: "DO NOT ship").
- **HMAC symmetric key from config** — every verifier needs the secret. Production wants
  either a securely distributed shared secret or asymmetric (RS/ES) signing so verifiers
  only hold the public key, plus key rotation.
- **No sliding expiry / absolute session lifetime cap** — a refreshing session lives
  forever. Add an absolute max age.
- **No cleanup** of expired/revoked sessions — the map grows unbounded.

---

## 30 · Tiered Rate Limiting (`tiered-rate-limit-poc`, port 8083)

### The hard problem
Enforce per-plan quotas that **absorb bursts** but cap steady-state rate, across **three
independent dimensions** (user, IP, endpoint) simultaneously, and return a precise
`Retry-After` when any one trips.

### What we are protecting
Service availability and fairness: paying tiers get their quota, abusers get contained, and
honest clients get actionable back-pressure.

### Solution shape
A **token-bucket per dimension**. Each request draws one token from three buckets at once —
the user's tier bucket, the IP bucket, the endpoint bucket. If *any* bucket is empty, the
request is a `429`; the `Retry-After` is the **max** of the deficits (the longest wait wins).
Buckets **refill lazily** (computed on read from elapsed time) so there's no scheduler
thread. Capacity = max burst; refill rate = steady-state throughput. Tiers map to
`(capacity, refillTokens, interval)`.

### Key tech by responsibility

| Responsibility | Mechanism |
|----------------|-----------|
| Burst + steady-state in one primitive | `TokenBucket` — capacity for burst, refill rate for steady state ([TokenBucket](tiered-rate-limit-poc/src/main/java/com/vndirect/poc/ratelimit/domain/TokenBucket.java)) |
| No scheduler thread | Lazy refill in `refill()` computed from `elapsed × tokensPerMs` on each access |
| Plan → limits | `Tier` enum: FREE 20/min, PRO 120/min, ENTERPRISE 600/min |
| Three-dimensional check | `RateLimitService.check` consults user/IP/endpoint buckets, all must pass ([RateLimitService:37](tiered-rate-limit-poc/src/main/java/com/vndirect/poc/ratelimit/service/RateLimitService.java:37)) |
| Accurate retry hint | `tryConsume` computes `ceil(deficit / tokensPerMs)`; service takes the max across dims |
| Correct HTTP semantics | Controller returns `429` + `Retry-After` header, or `200` + `X-RateLimit-*` |
| Thread safety | `synchronized tryConsume/snapshot`; dimension maps are `ConcurrentHashMap` |

### How it solves each sub-problem
- **Per-plan quotas** → tier drives the user bucket's `(capacity, refill)`; upgrade = swap
  the bucket.
- **Absorb bursts** → a full bucket lets a client spend `capacity` tokens instantly, then
  throttles to the refill rate.
- **Three dimensions, independent** → three separate buckets; one hot endpoint empties only
  the endpoint bucket, leaving the user's other-endpoint quota intact; one abusive user
  empties only their bucket, not the shared IP pool's headroom for others.
- **Tell me when to retry** → deficit ÷ refill-rate gives exact ms; the response uses the
  longest across the tripped dimensions so a retry actually succeeds.

### Tech debt to acknowledge
- **Per-pod buckets** — the maps live in one JVM. With N replicas the effective limit is
  N× the intended one. Production needs an atomic **Redis Lua** `refill→check→decrement`
  keyed by dimension (the source comment says exactly this). See [CONSISTENCY.md](CONSISTENCY.md).
- **Tiers are a hardcoded enum** — real systems read a `tier_quotas` table so plan changes
  are runtime config, not a redeploy.
- **IP and endpoint tiers are hardcoded** (IP→PRO cap, endpoint→ENTERPRISE cap) rather than
  policy-driven.
- **Unbounded key growth** — IP and endpoint maps never evict; needs TTL/LRU.
- **No cost weighting** — every call costs 1 token; expensive endpoints should cost more.
- **Wall-clock refill** (`System.currentTimeMillis()`) is fine single-node but drifts across
  hosts; a distributed version must rely on the Redis server clock, not the app's.
