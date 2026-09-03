# CONSISTENCY.md — Scaling the POCs from 1 JVM to N pods / N VMs

The POCs are single-process. Every primitive — `AtomicInteger`, `ConcurrentHashMap`,
`ScheduledExecutorService` — lives inside one JVM's memory. That is why they
are simple, fast, and correct.

**The moment you scale horizontally, every one of those primitives lies.** Two
pods each have their own `AtomicInteger`, each thinks stock = 10, and together
they sell 20 units of a 10-unit SKU without ever oversselling *locally*.

This document walks through, POC by POC, what must move where when you go from
one JVM to N pods behind a Kubernetes Service (or N VMs behind a load balancer).

---

## 1. The problem in one picture

```
                       ┌────────────────┐
   client ───▶ LB ─┬─▶ │  pod-A         │  stock = 10 (local AtomicInteger)
                   │   └────────────────┘
                   │   ┌────────────────┐
                   └─▶ │  pod-B         │  stock = 10 (local AtomicInteger)
                       └────────────────┘

   pod-A sells 10, pod-B sells 10, warehouse ships 20, ops has 10. Bad.
```

Solving this needs one of three shapes:
1. **Shared state** — move the counter to a system whose atomics are shared
   (Redis, a DB row).
2. **Partitioned state** — make sure every request for the same SKU lands on
   the same pod (Kafka partitioning, sticky routing).
3. **Reconciled state** — accept eventual consistency + a reconciliation loop.
   Only appropriate for read-mostly caches, never for stock.

For a real e-commerce backend the answer is almost always shared state
(Redis + DB), sometimes with partitioning as an additional layer.

---

## 2. POC 1 — Reservation with TTL

### What breaks under horizontal scale
- `stock` is a local `AtomicInteger`. Each pod counts down independently.
- `reservations` is a local `ConcurrentHashMap`. Pod A cannot confirm a
  reservation created by pod B.
- The `sweeper` fires on every pod. With 10 pods you have 10 sweepers
  competing on nothing (or worse, each pod sweeping only its own subset
  of holds — orphaning holds when a pod dies).

### What to move where

| Concern | POC | Production |
|---------|-----|------------|
| Stock counter | `AtomicInteger` per SKU | Redis `INCR/DECR` on `stock:{sku}` in a Lua script, **or** DB row with `UPDATE inventory SET qty = qty - :n WHERE sku = :s AND qty >= :n` |
| Reservation registry | `ConcurrentHashMap<id, Reservation>` | DB table `reservations(id PK, user, sku, qty, expires_at, status)` — the DB is source of truth; Redis is a hot cache of active holds |
| Terminal-state CAS | `ConcurrentHashMap.replace` | `UPDATE reservations SET status = 'CONFIRMED' WHERE id = ? AND status = 'ACTIVE'` — check `affectedRows == 1` |
| Sweeper | in-process `ScheduledExecutorService` | Either (a) a **leader-elected singleton** (K8s lease / ZooKeeper / Redis SETNX with TTL) that runs the sweep, or (b) each row has `expires_at` as an index and any pod runs a bounded `SELECT ... WHERE status='ACTIVE' AND expires_at < NOW() LIMIT 100 FOR UPDATE SKIP LOCKED` — Postgres pattern |

### Redis Lua for atomic reserve
```lua
-- KEYS[1] = stock key, KEYS[2] = reservation hash
-- ARGV = qty, reservation_id, user, sku, ttl_ms
local avail = tonumber(redis.call('GET', KEYS[1]) or '0')
if avail < tonumber(ARGV[1]) then return 0 end
redis.call('DECRBY', KEYS[1], ARGV[1])
redis.call('HSET', KEYS[2], 'user', ARGV[3], 'sku', ARGV[4], 'qty', ARGV[1], 'status', 'ACTIVE')
redis.call('PEXPIRE', KEYS[2], ARGV[5])   -- Redis itself sweeps
return 1
```
Redis key-expiry replaces the in-process sweeper. `HGET status` after expiry
returns nil — that IS the "expired" state. Persist the actual booking to the
DB before you tell the user "you got it".

### Sub-problems addressed by each layer
| Sub-problem | Redis role | DB role |
|-------------|-----------|---------|
| Hot path check-and-decrement | atomic Lua on one key | `UPDATE ... WHERE qty >= ?` when Redis is unavailable |
| TTL cleanup | `PEXPIRE` — automatic | index scan for stragglers after Redis outage |
| Source of truth for refunds | — | reservation row |
| Idempotency of confirm | `HSETNX status CONFIRMED` | unique constraint on `(reservation_id, terminal_status)` |

### Failure modes to design for
- **Redis is down.** Do NOT keep serving from a local cache — that's exactly
  how you oversell. Either read-through the DB with `SELECT ... FOR UPDATE`
  or degrade gracefully to "high demand, try again in a moment."
- **Pod dies mid-reserve.** The DECRBY already happened. Two options:
  (a) rely on TTL — the hold expires naturally; (b) write an outbox row
  before responding so a recovery job knows to attach it to the user or
  release it.
- **Clock skew across pods.** Never compute `expires_at` on the pod. Compute
  it in Redis / DB — one clock authoritative for the fleet.

---

## 3. POC 2 — Oversell strategies

### What breaks under horizontal scale
Every strategy in POC 2 relies on a single JVM-local memory cell:

| Strategy | POC state | Fails horizontally because |
|----------|-----------|----------------------------|
| Synchronized | `int stock` + monitor | Monitors don't cross JVMs |
| Atomic CAS | `AtomicInteger` | Same |
| Optimistic (`@Version`) | `AtomicLong` version + `volatile int` | ONLY the JPA/DB version works across pods — POC just mimics the shape |
| Queue-serialised | 1 executor thread | Only fine if all requests for a SKU hit the same pod |

### Production mapping

| POC strategy | Production analogue | Where to use |
|--------------|--------------------|--------------| 
| Synchronized | Row-level DB lock (`SELECT ... FOR UPDATE`) | Low-QPS admin tools; not for hot SKUs |
| **Atomic CAS** | **Redis Lua DECRBY-if-≥-qty** or **`UPDATE ... WHERE stock >= ?`** with `affectedRows` check | Default for hot SKUs |
| Optimistic (`@Version`) | JPA `@Version` — DB row `WHERE id=? AND version=?` | General CRUD; not for extreme contention |
| Queue-serialised | **Kafka partition keyed by SKU** — one consumer per partition | When business rules per SKU are richer than a CAS: combo caps, per-user quota, fraud checks |

### The Kafka-partitioned pattern (queue-serialised at scale)

```
   API pods ──▶  produce({sku, user, qty, corr_id}, key=sku)  ──▶  Kafka topic (N partitions)
                                                                        │
                                                                        ▼
                                                          one consumer per partition
                                                       ┌─────────────────────────────┐
                                                       │  per-consumer local state:  │
                                                       │  stock[sku], per-user limit │
                                                       │  ── serialised by design ── │
                                                       └─────────────────────────────┘
                                                                        │
                                                                        ▼
                                          write outcome (accepted / rejected) to result topic
                                          API pod polls / long-polls for corr_id
```

Because the partition key is `sku`, every request for SKU-X lands on the same
consumer, which processes them one at a time. **All the tricky concurrency
disappears.** The trade-off is added latency (one Kafka round-trip) and that
you now own consumer-lag alerts, rebalance handling, and DLQ.

### Failure modes
- **Two consumers on one partition** during a rebalance — Kafka semantics
  prevent this if you commit correctly. Use manual commits, commit *after*
  the DB write, and be idempotent on `corr_id`.
- **Consumer crashes with uncommitted state** — every accept must be
  durable (DB write) before the Kafka commit. Otherwise a rebalance
  replays it and you double-decrement.
- **Hot partition** — one viral SKU dominates one partition. Sub-partition
  by `hash(sku, user_bucket)` — same idea as POC 3's bucketing, applied at
  the partitioning layer.

---

## 4. POC 3 — Flash sale under horizontal scale

### What breaks
- `AdmissionGate` — every pod has its own view. 10 pods × 5000 in-flight
  = 50k in-flight for real. The per-user rate limit sees the same user
  from different pods and lets them all in.
- `BucketedInventory` — 10 pods × 16 buckets × 6 units each = 960 units
  of "local stock" for a 100-unit SKU. Massive oversell.

### The production shape — three tiers, cheapest first

```
   ┌────────────────────────────────────────────────────────────┐
   │  Edge (CDN + WAF)                                          │
   │  - Cloudflare / Fastly bot mitigation                      │
   │  - Rate limit by IP / by session before request hits VPC   │
   └───────────────────────┬────────────────────────────────────┘
                           ▼
   ┌────────────────────────────────────────────────────────────┐
   │  Ingress (NGINX / Envoy)                                   │
   │  - Redis-backed token bucket (per user, per SKU)           │
   │  - Global in-flight semaphore (per SKU)                    │
   │  - Virtual queue                                           │
   └───────────────────────┬────────────────────────────────────┘
                           ▼
   ┌────────────────────────────────────────────────────────────┐
   │  App pods (many)                                           │
   │  - Bucketed decrement in REDIS, not in memory              │
   │  - Bucket keys: stock:{sku}:{0..15}                        │
   │  - Random / hash-based bucket selection, walk on miss      │
   └───────────────────────┬────────────────────────────────────┘
                           ▼
   ┌────────────────────────────────────────────────────────────┐
   │  Order pipeline (Kafka + DB)                               │
   │  - Every accepted decrement writes an outbox event         │
   │  - Reconciler compares Redis sum vs DB sum every N sec     │
   └────────────────────────────────────────────────────────────┘
```

### Mapping the POC to that stack

| POC component | K8s / production equivalent |
|---------------|-----------------------------|
| Per-user `Bucket` (1-s rate limit) | Redis token-bucket Lua script keyed by `rl:{userId}` |
| `inFlight` counter | Redis `INCR/DECR` on `inflight:{sku}` with a Lua-checked cap, **or** Envoy's `local_ratelimit` filter (per-pod fine when the LB round-robins) |
| `perUser` map | Redis hash. Never keep this in pod memory — a bot spraying across pods bypasses it. |
| `BucketedInventory` (16 in-memory buckets) | 16 Redis keys `stock:{sku}:{0..15}`, atomic Lua decrement across a random key with fallback |
| `walks` metric | Prometheus counter incremented by the Lua script |

### Why the buckets belong in Redis, not per-pod
If pod-local buckets held stock, adding a pod would create stock. The
inventory *has* to live in one shared address space. Redis is chosen because
Lua is atomic, latency is a millisecond, and cluster mode gives you N-master
partitioning — the same "buckets" idea one layer up.

### Kubernetes-specific concerns
- **HPA in the middle of a flash sale.** Scale-up mid-event means new pods
  start warm-empty. Prewarm the JVM before the launch window (do a
  synthetic warmup right after readiness).
- **Rolling deploy during a sale.** Just don't. Freeze deploys around
  known flash-sale windows.
- **Pod eviction / OOMKilled.** Every accepted admission must be written to
  Redis before the response returns. A killed pod loses the in-memory
  request, not the accepted state.
- **Node failure.** Redis is the source of truth; the app pod is
  stateless. Kill any pod, another admits.

---

## 5. POC 4 — Coupon engine

### What breaks
Less than the others — coupon math is per-request, stateless. But:
- **Per-user redemption caps** (`"this coupon can be used 1x per user"`)
  need a shared counter.
- **Global redemption caps** (`"first 1000 uses"`) need a shared counter
  that decrements atomically.
- **Best-combo caching** — if you cache "best combo for cart X" locally,
  two pods diverge on coupon inventory changes.

### Production mapping

| Sub-problem | Storage |
|-------------|---------|
| Coupon definitions | DB. Read-heavy, cache in per-pod memory with a short TTL (`~30s`) — divergence for 30s is acceptable for definitions but never for redemption counters. |
| Per-user redemption count | Redis `INCR` on `coup:{code}:used:{userId}` with a Lua "check-then-incr" guarded by the max-per-user rule. |
| Global redemption cap | Same shape as inventory: atomic decrement of a Redis-backed counter. Treat it like stock. |
| Audit trail | Written to DB (source of truth) + shipped to a log/analytics topic for fraud. |
| Best-combo result cache | Per-pod LRU keyed by `(cart_hash, wallet_hash)`. Read-mostly, tolerates divergence. Never persisted. |

### Where consistency matters
- The redemption counter is where a naive implementation gives away too
  many discounts. Atomic decrement, source of truth in Redis, mirrored to
  DB.
- The audit trail must be **write-through**: the discount is not "given"
  until the audit row is committed. A cache-only audit is not an audit.

---

## 6. POC 5 — Fulfillment routing

### What breaks
Routing itself is a pure function — same input, same plan. What breaks is
**the inventory input**:

- Two pods routing simultaneously each see the same "warehouse HCM-01 has 5
  units of SKU-A" and each plan to take 4. That is oversell one layer up.

### Solution shape at scale

1. **Reservation before routing.** Before you plan, atomically reserve
   inventory across warehouses (same primitive as POC 1). If reservation
   fails, replan.
2. **Routing is stateless.** Run it on any pod. It's cheap; horizontal
   scale is free.
3. **Warehouse inventory is source-of-truth in the DB / WMS.** Cached in
   Redis for read latency, invalidated on writes.
4. **Optimistic outcome, corrective reroute.** Publish an outbox event
   `plan_accepted(order, shipments)` to Kafka. The WMS confirms per-line.
   If a warehouse rejects (physical count differs from system), re-run
   routing on the affected lines.

### K8s concerns
- **Routing is CPU-bound and short.** Set aggressive CPU limits — a stuck
  optimizer doesn't starve neighbors.
- **Warehouse master data as ConfigMap vs DB.** For 50+ warehouses use the
  DB; ConfigMaps don't scale to per-warehouse-per-SKU inventory.

---

## 7. Cross-cutting: making N pods behave like one system

### 7.1 Shared state should have one owner
For each unit of critical state, pick **exactly one system** to own it. Anything
else is a cache with a defined staleness budget.

| Kind of state | Owner | Cache locations |
|---------------|-------|-----------------|
| Stock counter | Redis (primary) → DB (persistent) | Local pod cache = **none** for writes; read-through only |
| Reservation | DB | Redis for the hot lookup |
| Coupon definition | DB | Per-pod cache with 30-s TTL |
| Coupon redemption count | Redis | — |
| Session / cart contents | Redis | — |
| Warehouse inventory | WMS / DB | Redis short-TTL cache |
| Fulfillment plan | (transient — recomputed) | — |

### 7.2 Distributed atomicity toolkit
- **Redis Lua scripts** — the go-to primitive for compound atomic ops
  across multiple keys. Runs single-threaded on the shard. Same mental model
  as `AtomicInteger` at pod scale.
- **DB `UPDATE ... WHERE`** with `affectedRows` check — durable version
  of the same idea. Slower, but survives Redis failure.
- **Kafka partitioning by key** — the queue-serialised strategy at
  cluster scale. All operations on one entity land on one consumer, in
  order.
- **Idempotency keys everywhere** — every mutating endpoint accepts an
  `Idempotency-Key` header, deduped in Redis. Retries become safe by
  construction.
- **Leader election** for singletons (sweepers, reconcilers) — K8s Lease
  or Redis `SET NX EX` with a heartbeat.

### 7.3 What Kubernetes gives you and what it does NOT
Kubernetes gives you:
- **Horizontal replicas** (Deployments + HPA)
- **Rolling updates** with pod-disruption budgets
- **Readiness / liveness** — traffic flows only to healthy pods
- **ConfigMaps / Secrets** for config
- **Services** for stable DNS + LB in front of pod IPs

Kubernetes does NOT give you:
- **Shared state across pods.** That's Redis / DB / Kafka.
- **Ordering guarantees.** Two requests from one user can land on two pods
  in reversed order. Design idempotency + causal keys.
- **Automatic consistency.** HPA scale-up doesn't warm caches; scale-down
  doesn't drain in-flight requests unless you configure `preStop` + long
  enough `terminationGracePeriodSeconds`.
- **Distributed locks.** Redis lock or DB advisory lock — your call, not
  the platform's.

### 7.4 Reconciliation as a safety net
Any critical counter (stock, redemption count, in-flight) needs a
**reconciliation job** that periodically compares Redis sum vs DB sum
and alerts / repairs on divergence. Don't reconcile silently — an
alert is the signal that a bug leaked through.

```
   every 60s:
     redis_sum   = SUM(GET stock:{sku}:{0..15}) for all SKUs
     db_sum      = SELECT sku, SUM(qty) FROM inventory GROUP BY sku
     divergence  = redis_sum vs db_sum
     if abs(divergence) > threshold: page ops
```

### 7.5 What we intentionally do NOT try to solve here
- **Strong consistency across a multi-region deployment.** That is a
  different design (Spanner-class or event-sourced with region ownership),
  not a single-region K8s answer.
- **Byzantine-fault-tolerance.** Assumed no. Pods are trusted, only crash
  faults are considered.
- **Split-brain during a Redis failover.** Use Redis Sentinel / Cluster
  with `min-replicas-to-write` and accept a brief write-unavailability
  over a split-brain oversell.

---

## 8. TL;DR — one-line mapping

| POC primitive | K8s / VM production form |
|---------------|--------------------------|
| `AtomicInteger stock` | Redis key + Lua CAS, mirrored to DB row |
| `ConcurrentHashMap reservations` | DB table, source of truth |
| `ScheduledExecutorService sweeper` | Leader-elected singleton, or per-row Redis TTL |
| `synchronized` monitor | Row-level `SELECT ... FOR UPDATE` |
| `AtomicInteger[] buckets` | N Redis keys with atomic Lua decrement + walk |
| `AdmissionGate` (per-user rate) | Redis token bucket at ingress + Envoy `local_ratelimit` |
| Queue-serialised single-thread worker | Kafka topic partitioned by SKU + 1 consumer per partition |
| Coupon audit `ArrayList<AuditEntry>` | DB write-through + Kafka analytics topic |
| In-memory best-combo cache | Per-pod LRU, no coordination needed |
| Routing pure function | Stateless, any pod; inventory reservation happens BEFORE routing |

The POCs are the correct **mental model**. The production stack is the
correct **implementation** of that model at N-pod scale.
