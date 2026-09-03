# CONSISTENCY.md — Correctness when you scale out (k8s pods / multiple VMs)

Every POC in this suite runs correctly on **one** process. This document is about what
breaks the moment you run **two or more replicas** behind a load balancer — whether those
replicas are Kubernetes pods (`kubectl scale deployment … --replicas=N`, or an HPA scaling
on CPU) or plain VMs behind an nginx/HAProxy/L4 balancer.

The failure mode is always the same shape: **critical state lives in-process, so each
replica has its own private copy of "the truth."** A load balancer then spreads a single
user's requests across replicas that disagree.

```
                 ┌─────────────┐
   request 1 ───▶│   Pod A     │  in-memory state A   ← its own buckets / sessions / DB
   request 2 ───▶│   Pod B     │  in-memory state B   ← disagrees with A
   request 3 ───▶│   Pod C     │  in-memory state C   ← disagrees with both
                 └─────────────┘
   A load balancer has no idea these three disagree.
```

> **The one-line rule:** any state that must be *the same for a user regardless of which
> replica answers* has to move **out of the JVM heap** and into a shared, atomic store —
> in practice **Redis** for hot ephemeral state and the shared **Oracle** DB for durable
> records.

---

## Per-POC analysis

### 30 · Tiered Rate Limiting — *the most obviously broken at scale*

**What breaks.** `RateLimitService` keeps `userBuckets`, `ipBuckets`, `endpointBuckets` in
`ConcurrentHashMap`s **inside one JVM**. Run 3 pods and a user round-robined across them
draws from 3 separate buckets → the real limit is **3× the intended limit**. Scale to 10
pods on an HPA and Free users effectively get Enterprise throughput. Worse, the multiplier
*changes as the HPA scales*, so the limit is non-deterministic.

**Why sticky sessions don't save you.** You could pin a user to one pod (session affinity),
but: the IP and endpoint dimensions are cross-user by definition (many users share an IP /
an endpoint), affinity breaks on pod restart/rebalance, and it defeats the point of
horizontal scaling.

**Fix — centralize the bucket in Redis with an atomic Lua script.** The bucket algorithm is
unchanged; only its *home* moves. One `EVAL` does refill → check → decrement atomically so
concurrent replicas can't double-spend:

```lua
-- KEYS[1] = bucket key (e.g. rl:user:free-user)
-- ARGV = capacity, refill_tokens, interval_ms, now_ms, cost
local b = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(b[1]) or tonumber(ARGV[1])
local ts     = tonumber(b[2]) or tonumber(ARGV[4])
local elapsed = math.max(0, tonumber(ARGV[4]) - ts)
tokens = math.min(tonumber(ARGV[1]),
                  tokens + elapsed * (tonumber(ARGV[2]) / tonumber(ARGV[3])))
local allowed = tokens >= tonumber(ARGV[5])
if allowed then tokens = tokens - tonumber(ARGV[5]) end
redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', ARGV[4])
redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[3]) * 2)   -- also fixes unbounded key growth
return { allowed and 1 or 0, math.floor(tokens) }
```

- **Atomicity:** Redis runs the whole script single-threaded → no read-modify-write race
  across pods.
- **Clock:** use `now_ms` from the *Redis* server (`redis.call('TIME')`) — never the pods'
  wall clocks, which drift.
- **Key growth:** `PEXPIRE` evicts idle IP/endpoint keys automatically.
- **Availability trade-off:** Redis is now on the request hot path. Use a fast local
  connection pool, and decide the **fail-open vs fail-closed** policy if Redis is
  unreachable (fail-open for rate limiting is usually acceptable; log it).

---

### 29 · Multi-Device Sessions — *silent security hole at scale*

**What breaks.** `SessionService` holds `usersById`, `sessions`, and the per-user
`tokenVersion` **in-memory per pod**.

- "Logout everywhere" bumps `tokenVersion` **only on the pod that served the request.** Pod
  B still has the old version, so a JWT the user thinks they revoked keeps validating on B.
  **This is a real security failure, not just wrong numbers.**
- Per-device `sessions` created on Pod A don't exist on Pod B, so `validateAccess` returns
  "session not found" for a legitimate token whenever the LB routes to a different pod.
- Refresh-token rotation is a check-and-swap on a per-pod map, so concurrent refreshes on
  different pods can both succeed and neither detects the theft they're supposed to.

**What already scales correctly.** The **JWT signing secret** comes from config, so every
pod verifies signatures identically — good. Keep that property.

**Fix — move session + token-version state to Redis (Spring Session fits directly).**

| State | Where it goes | Notes |
|-------|---------------|-------|
| `tokenVersion` per user | Redis `INCR user:{id}:tv` | Single source of truth; every pod reads it during `validateAccess`. |
| `sessions` (per device) | Redis hash / Spring Session | Any pod can look up / revoke any session. |
| refresh JTI rotation | Redis atomic `GETSET`/Lua on the session | Makes rotation + reuse-detection race-free across pods. |

- Validation stays cheap: signature + expiry locally, then two O(1) Redis reads (`tv`,
  session-revoked). You can cache `tv` per pod with a short TTL (e.g. 1–5s) to cut Redis
  reads — accepting that revocation is eventually consistent within that TTL. Choose the TTL
  as an explicit security/perf trade-off; **0 = strongly consistent revocation.**
- **DAccount already uses Redis for Spring Session** (see the parent project's
  `CLAUDE.md`), so this is the natural target, not a new dependency.

---

### 28 · Account Merge — *shared DB helps, but concurrency still bites*

**What's already better.** Merge state lives in the database, not the heap. Point every pod
at the shared **Oracle** instance (the POC's in-memory H2 is per-pod and would itself be a
consistency bug — different pods would see different data) and the redirect tombstones are
visible to all pods immediately after commit.

**What still breaks under concurrency / multiple pods.**

1. **Concurrent merges racing.** Two operators (or two pods) merging overlapping accounts at
   once. The merge reads both users, then bulk-updates FKs; without locking, interleavings
   can double-move rows or create **A→B and B→A redirect cycles**. The code's 8-hop cycle
   guard is a *safety net that throws*, not a fix.
   **Fix:** `SELECT … FOR UPDATE` on **both** user rows at the start of the transaction, in a
   **deterministic id order** (always lock lower id first) to avoid deadlocks. Add a DB
   `CHECK`/trigger or app rule forbidding a redirect that points back into the chain.

2. **Isolation level.** The preview→execute gap means the data can change between the
   operator seeing conflicts and confirming. Re-validate inside the transaction; consider an
   optimistic-lock `@Version` on `User` so a stale confirm fails loudly.

3. **Login racing with a merge.** A login on the source's Google method while the merge is
   mid-flight must resolve deterministically. Because `followRedirect` re-reads from the DB,
   once the tombstone is committed it's correct; the FOR UPDATE lock closes the in-flight
   window.

4. **Bulk JPQL bypasses the L1/L2 cache.** Across pods, second-level caches (if enabled) can
   serve stale users after a merge. Evict on merge or keep merged entities out of the L2
   cache.

5. **Redirect chains + flattening job.** A background job that flattens A→B→C to A→C and
   B→C must itself be safe to run on multiple pods → guard it with a **distributed lock**
   (Redis `SET NX PX` or a DB advisory lock) so only one pod runs it at a time.

---

## Kubernetes-specific concerns

These apply once the workload is a `Deployment` with `replicas > 1` or an HPA.

- **Rolling deploys run mixed versions.** During a rollout, old and new pods serve traffic
  simultaneously. Any change to the Redis key schema or JWT claim shape must be
  **backward-compatible for one release** (expand → migrate → contract).
- **Pods are cattle.** `SIGTERM` can arrive any time (scale-down, node drain, spot
  reclaim). Never keep authoritative state only in a pod → it dies with the pod. This is
  exactly why sessions/buckets must be external.
- **Graceful shutdown.** Set `terminationGracePeriodSeconds` and a `preStop` hook so
  in-flight merges (a transaction) can finish or roll back cleanly; keep merge transactions
  short.
- **HPA feedback loop with rate limiting.** If you *don't* centralize buckets, scaling up to
  shed load also raises the effective limit — the limiter fights the autoscaler. Centralized
  Redis buckets keep the limit constant regardless of replica count.
- **Readiness vs liveness.** A pod that has lost its Redis/Oracle connection should fail
  **readiness** (stop receiving traffic) but not necessarily **liveness** (don't crash-loop);
  otherwise a brief Redis blip cascades into a full restart storm.
- **Redis/Oracle become shared SPOFs.** Run Redis with replication/Sentinel or clustered;
  budget for its latency on the hot path; define fail-open/fail-closed per feature
  (rate-limit: fail-open acceptable; session revocation: prefer fail-closed).
- **No sticky sessions required.** The whole point of externalizing state is that any pod can
  serve any request. Avoid session affinity as a crutch — it hides the real bug and breaks on
  rebalance.

---

## Summary table

| POC | State today | Breaks at N replicas because… | Production home | Consistency primitive |
|-----|-------------|-------------------------------|-----------------|-----------------------|
| 28 Account Merge | H2 in-memory (should be Oracle) | per-pod H2 diverges; concurrent merges race → cycles | Shared Oracle | `SELECT … FOR UPDATE` (ordered) + `@Version` + distributed lock for the flatten job |
| 29 Sessions | `ConcurrentHashMap` per pod | revoke/token-version invisible to other pods (security hole) | Redis (Spring Session) | atomic `INCR` for `tv`; shared session store; Lua for refresh rotation |
| 30 Rate Limit | `ConcurrentHashMap` per pod | N pods → N× the limit, non-deterministic under HPA | Redis | single atomic Lua `refill→check→decrement` + server clock + key TTL |

**Bottom line:** scaling these POCs is not a code-rewrite — the algorithms are already
right. It's a **relocation of state**: pull the authoritative bucket/session/token-version
out of the JVM into Redis (with atomic ops), point the merge at shared Oracle with proper
row locking, and make every replica stateless so the load balancer can route freely.
