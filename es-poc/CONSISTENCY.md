# CONSISTENCY — scaling the suite behind a k8s Service or across VMs

> The POCs assume **one JVM per POC**. That's fine for a demo. This document is what changes when you run the same app as **N pods** behind a Kubernetes `Service`, or on **N VMs** behind a load balancer.

If you skim only one section, read [The seven landmines](#the-seven-landmines) — that's the punch list.

---

## The topology we're scaling into

```
              ┌────────────────────┐
              │  L4 / L7 LB (k8s   │
              │  Service / ALB)    │
              └────┬────┬────┬─────┘
                   │    │    │       (round-robin, no affinity by default)
              ┌────▼┐ ┌─▼─┐ ┌▼───┐
              │ pod │ │pod│ │pod │  ← N instances of the same POC JAR
              └──┬──┘ └─┬─┘ └─┬──┘
                 │      │     │
                 ▼      ▼     ▼
        ┌──────────────────────────┐
        │   Postgres (primary)     │  single writer
        └──────────────────────────┘
        ┌──────────────────────────┐
        │   Elasticsearch (3-node) │  shard-distributed
        └──────────────────────────┘
        ┌──────────────────────────┐
        │   Kafka  /  Redis        │  shared
        └──────────────────────────┘
```

**Key property**: any request can land on any pod. The pod is *stateless with respect to routing*. Anything the app remembers in-JVM is invisible to the other N-1 pods.

The single-JVM assumptions the POCs make don't all survive that.

---

## The seven landmines

| # | Landmine | Affected POCs | Fix summary |
|---|---|---|---|
| 1 | **Multiple outbox pollers racing on the same rows** | [db-to-es-sync-poc](./db-to-es-sync-poc/) | `pg_try_advisory_lock` or `FOR UPDATE SKIP LOCKED` |
| 2 | **Dual-write / migration flag in JVM memory** | [es-zero-downtime-reindex-poc](./es-zero-downtime-reindex-poc/) | Move flag to Redis or a `migration_state` table |
| 3 | **Reindex triggered N times in parallel** | [es-zero-downtime-reindex-poc](./es-zero-downtime-reindex-poc/), reload endpoints | Leader election or a "started" record with unique key |
| 4 | **PIT / search_after cursors are pod-local** | [es-deep-pagination-poc](./es-deep-pagination-poc/) | Cursor is opaque and ES-side; already portable if state is client-carried |
| 5 | **Read-your-writes freshness token stuck on one pod** | [es-eventual-consistency-poc](./es-eventual-consistency-poc/) | Put the "just-wrote" flag in Redis with a TTL |
| 6 | **Kafka consumer group with wrong partition count** | [db-to-es-sync-poc](./db-to-es-sync-poc/) | `partitions ≥ pods` and keyed by entity ID |
| 7 | **Bulk-load index settings toggled per-pod** | [es-bulk-indexing-poc](./es-bulk-indexing-poc/) | Toggle from a controller pod (or job), not from every worker |

Each is unpacked below. **The theme**: state that lived in a `@Component` singleton now needs to live in Postgres, Redis, ES itself, or the LB layer.

---

## 1. Outbox under multi-pod

### The bug
`OutboxPoller` is a `@Scheduled` bean. In one pod: one poller draining rows. In three pods: **three** pollers all doing `SELECT ... FROM outbox_event WHERE published = false ORDER BY id LIMIT 500`. They all read the same batch, all publish to Kafka, all mark the same rows published. Kafka gets triple-published messages.

At-least-once with idempotent apply *tolerates* this (ES `version_type: external` rejects the older duplicates). But throughput drops, Kafka rebalances thrash, and log lines get confusing.

### The fix
Two choices, both correct:

**A. Advisory lock (leader-per-tenant, simple)**
```sql
-- inside the poller, before SELECT:
SELECT pg_try_advisory_lock(hashtext('outbox-poller'));
-- if false → skip this tick; another pod holds it.
```
One pod effectively becomes the leader for outbox draining. Zero code churn if you're already on Postgres. Trade-off: single point of throughput; if one pod's poller is slow, the queue lags.

**B. `FOR UPDATE SKIP LOCKED` (share the work)**
```sql
SELECT * FROM outbox_event
 WHERE published = false
 ORDER BY id
 LIMIT 100
 FOR UPDATE SKIP LOCKED;
```
Each pod reserves 100 rows, no other pod sees them. Genuine parallelism. Preferred when outbox lag is a bottleneck.

Both are one-line changes to `OutboxPoller` — the comment in the POC's code marks the spot.

### What NOT to do
- **Kubernetes `singleton` deployment** — solves the race but takes you back to one pod, which loses HA. Only valid for very low-traffic services.
- **App-level `synchronized`** — obviously local to a JVM. No effect across pods.
- **Leader election via a config map** — works but requires a k8s coordinator; heavier than a DB advisory lock for the payoff.

---

## 2. Dual-write flag under multi-pod

### The bug
`MigrationState` in the reindex POC is a `@Component` bean. When you POST `/admin/migration/start`, only **that pod's** in-memory flag flips. Writes routed to other pods still go to v1 only. When the alias swap happens, v2 is missing all writes that landed on other pods during the migration window.

### The fix
Persist the migration state where every pod sees it:

**A. Redis (recommended for this use case)**
```
SET migration:products state=DUAL_WRITE started=<ts> reindexTaskId=<id>
```
Every write checks Redis once per request (cache locally for 1s if the read cost matters). A watcher on Redis keyspace notifications flips the local cache.

**B. Postgres `migration_state` table**
```sql
CREATE TABLE migration_state (
  name TEXT PRIMARY KEY,
  state TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
```
Cheaper if you already have Postgres and want no new dependency. Poll every N seconds or use `LISTEN/NOTIFY`.

The POC's `MigrationState` interface stays the same; the impl swaps.

---

## 3. Reindex triggered N times

### The bug
An operator hits `/admin/migration/start` once. The k8s Service routes to pod-1. But — for robustness — you added a "if state has been in `PREPARING` for > 30s, retry" watchdog. Under a network hiccup, pod-2 also flips to `PREPARING` and calls `_reindex`. Now two `_reindex` tasks copy v1 → v2. ES tolerates this (both are idempotent index-by-doc-ID overwrites) but doubles the CPU.

### The fix
The migration start is a **claim, not a request**. Use the same Redis / Postgres row from landmine 2, and make the state transition **conditional**:

```sql
UPDATE migration_state
   SET state = 'PREPARING', owner_pod = :myPod, updated_at = now()
 WHERE name = 'products' AND state = 'IDLE';
-- If 0 rows updated, another pod already started it. Bail.
```

Or in Redis: `SET migration:products PREPARING NX EX 3600` — `NX` = only if not exists.

Same pattern applies to the loader endpoint (`POST /admin/reload?count=1000000` in the deep-pagination POC): guard with a claim so N pods don't all start reloading 1M docs.

---

## 4. PIT / search_after cursors

### The bug
Not actually a bug — but worth pinning down because it *looks* like one.

`search_after` cursor = the sort-tuple of the last hit. Client-carried. Any pod can resume from it. **No pod affinity needed.**

PIT ID = opaque handle managed by ES itself. Also client-carried. Any pod can query with it. When the client stops using it, ES cleans it up on TTL.

**The subtlety**: PIT keepalive resets every time the PIT is used. If a client stops sending requests, ES cleans up after the configured TTL (`keep_alive: "5m"` in the POC). If your k8s pod that opened the PIT dies, the *client* still holds the ID; a new pod happily continues the export.

### What you have to do
Nothing. Both cursor formats are pod-portable. Just don't cache the cursor server-side "for the user's session" — that would tie the flow to a pod. Let the client carry it.

If you want observability, log `pod=<name> pitId=<id>` on open/close so you can correlate an abandoned PIT (ES metric) with a pod that died.

---

## 5. Read-your-writes under multi-pod

### The bug
`mode=read-through` in the eventual-consistency POC needs to know "did the caller just write this ID?" In the POC, this is a request-level query param — the client tells us.

For a real system you often want the **server** to remember, e.g. "for the next 2s after write to product X, prefer DB reads over ES for X." In one pod, a `ConcurrentHashMap<ProductId, Instant>` works. In three pods, the write lands on pod-1 and the follow-up read lands on pod-2 — which has no memory of the write.

### The fix
Move the "just-wrote" marker to Redis:

```
SETEX "fresh:product:123" 2 <writeTimestamp>
```

Every read checks Redis for `fresh:product:<id>`. If present, prefer DB. TTL of 2s means the entry naturally evaporates once ES has caught up.

Costs one Redis GET per read. Sub-millisecond, fine. Skip if the POC's client-hint pattern (`?just_created=true`) fits your app.

### What NOT to do
- **Sticky sessions to bind a client to one pod** — breaks the moment a pod dies and works only for the client that wrote; it doesn't help other viewers of the same object.
- **Rely on `refresh=wait_for` alone** — solves it for the client that wrote (they wait), doesn't help "other viewer" reads. Combine `wait_for` on the write with normal ES reads elsewhere.

---

## 6. Kafka consumer group / partition math

### The bug
The sync POC uses Kafka topic `sync.products.changes`. In one pod, one consumer handles all partitions. In three pods:

- **If topic has 1 partition**: still one consumer processes; the other two are idle. Order is preserved but throughput doesn't scale.
- **If topic has 3+ partitions but no key**: default partitioner spreads round-robin. Two updates to the same product may land on different partitions, get consumed in parallel, and apply in the wrong order → the older write is the surviving one.

### The fix
Two invariants:

1. **Partitions ≥ number of consumer pods.** Otherwise consumers are idle.
2. **Kafka message key = entity ID.** Then all events for the same entity land on the same partition, are consumed by the same consumer, and apply in order.

```java
kafkaProducer.send(new ProducerRecord<>(
    "sync.products.changes",
    product.id(),          // ← key, not null
    productEventJson));
```

Even with keyed partitioning, add the external-version guard on the ES write (`version_type: external, version = updated_at.toEpochMilli()`) as a belt-and-braces defense against replayed offsets.

---

## 7. Bulk-load settings toggled per-pod

### The bug
The bulk-indexing POC flips `refresh_interval=-1` and `replicas=0` on the index for the duration of the load. That's an *index-level* setting. Two pods both starting bulk loads: pod-1 sets `refresh=-1`, pod-2 finishes first and restores `refresh=1s` — while pod-1 is still loading. Pod-1's second half is now paying the refresh cost.

Worse: pod-1 finishes and restores `replicas=1`, which forces immediate replication of whatever pod-2 is still writing.

### The fix
Bulk-load settings are a **cluster-wide, per-index decision**, not a per-pod one. Two patterns:

**A. Dedicated loader job**  
Ingest is a k8s `Job` or CronJob, not a Deployment. One pod, one load, single owner of the index settings. Best for "nightly backfill" workflows.

**B. Coordinator role**  
Elect one pod (advisory lock, leader lease) to set/restore index settings; other pods just push docs into the shared bulk queue. Best for "continuous high-throughput ingest with many workers."

The POC has neither; it's a benchmark harness, not an ingest system. If you lift `BulkBenchmarkRunner` into prod, add option A around it.

---

## What is *already* pod-safe (no changes needed)

Worth naming so you don't over-engineer:

| POC | Reason it's fine |
|---|---|
| [es-deep-pagination-poc](./es-deep-pagination-poc/) | Cursors are stateless; ES handles PIT. |
| [es-relevance-tuning-poc](./es-relevance-tuning-poc/) | Query configs are code; eval is a read-only side channel. |
| [es-vietnamese-search-poc](./es-vietnamese-search-poc/) | Read-only search; no shared write state. |
| [es-autocomplete-poc](./es-autocomplete-poc/) | Read-only; each pod hits ES independently. |
| [es-hybrid-search-poc](./es-hybrid-search-poc/) | Read-only; embedding client is per-pod. |
| [es-faceted-search-poc](./es-faceted-search-poc/) | Read-only. |
| [es-observability-poc](./es-observability-poc/) | Admin endpoints; the underlying ES APIs are cluster-scoped. |
| [es-gotchas-poc](./es-gotchas-poc/) | Each gotcha's `/break` and `/fix` are single-request; they don't share state. |
| [es-shard-sizing-poc](./es-shard-sizing-poc/) | Calculator is pure; ILM install is idempotent (though see landmine 3 for the double-install case). |

---

## VM-specific notes (vs. k8s)

Most of what's above applies equally on VMs. Two differences worth calling out:

### Load balancing without cluster awareness
k8s Services give you readiness probes for free. Behind an AWS ALB or an nginx, **make sure your app exposes `/actuator/health/readiness`** and the LB is configured to use it. Otherwise the LB sends traffic to a pod that's still booting through Flyway migration or ES index creation.

### Rolling deploys and connection drain
On k8s, `preStop` hooks + `terminationGracePeriodSeconds` let you drain in-flight requests. On raw VMs, you need to add the equivalent:
```java
@PreDestroy
public void onShutdown() {
    // stop accepting new; wait for in-flight to finish; then close ES/Kafka clients.
}
```
Not doing this on the outbox poller means a pod being drained can leave a batch mid-publish; the next pod picks it up (idempotency covers you) but with a duplicate-Kafka-message hiccup.

### Shared filesystem is not portable
Some teams accidentally rely on writing to `/tmp` or a shared NFS mount. **Don't.** All state lives in Postgres / Redis / Kafka / ES. The pod filesystem is scratch.

---

## Scaling Postgres, ES, Kafka themselves

The app scales horizontally per the above. The **infrastructure** they talk to has its own scaling story — briefly:

### Postgres
- Single primary is fine up to tens of thousands of writes/sec if provisioned correctly.
- Read replicas offload eval queries and analytics — but *never* the outbox poller (replicas lag, you'd double-publish already-published events when a replica catches up).
- For the sync POC's CDC path: `wal_level=logical` and `max_replication_slots` sized to your Debezium fleet + 20% headroom.

### Elasticsearch
- **3-node minimum** for HA (quorum for master election).
- Dedicated master nodes at ~6 data nodes; before that, data nodes can master.
- Shard sizing → follow [es-shard-sizing-poc](./es-shard-sizing-poc/) calculator.
- Refresh interval defaults are for **user-facing indexes**; for time-series/log indexes bump to 30s+ to save merge cost.

### Kafka
- 3-broker minimum for RF=3 (which is what you want).
- KRaft mode (as in the POC) avoids Zookeeper. Suitable for modern Kafka.
- Partition count is a **one-way door**: adding partitions later breaks ordering guarantees for keyed messages. Overprovision at 2-3× current pod count.

### Redis
- Single node for the freshness-flag use case is fine; the data is ephemeral.
- If Redis is unavailable, degrade gracefully: skip the freshness check, accept a 1s window of stale reads. The whole point of the marker is soft-real-time; don't hard-fail the app on Redis outage.

---

## Consistency-vs-latency table (the honest tradeoff)

| Pattern | Sync guarantee across pods | Extra latency | Extra ops |
|---|---|---|---|
| **Outbox + advisory lock** | Eventual, seconds | 0 in the write path | +1 poller instance elected |
| **Outbox + SKIP LOCKED** | Eventual, seconds | 0 in the write path | 0 (all pods poll) |
| **CDC via Debezium** | Eventual, sub-second | 0 in the write path | Debezium infra |
| **`refresh=wait_for` on write** | Read-your-writes | Up to 1s per write | 0 |
| **Redis freshness flag** | Read-your-writes (across pods) | +1 Redis RTT per read | Redis dependency |
| **DB read-through on miss** | Read-your-writes (client-hinted) | +1 DB RTT on miss | 0 |
| **Sticky sessions** | Read-your-writes (single client) | 0 | Sticky-cookie config; harder deploys |
| **k8s single-replica** | Trivially consistent | 0 | No HA |

The suite recommends: **outbox + SKIP LOCKED** for writes, **`refresh=wait_for` + Redis freshness flag** for user-facing reads, **CDC** only when the ops team is comfortable owning it.

---

## Migration checklist for taking a POC to a multi-pod production deploy

Before merging a POC-derived service into a `Deployment` with `replicas: 3`:

- [ ] All `@Scheduled` jobs are guarded by advisory lock, leader lease, or moved to a k8s `Job`.
- [ ] All in-memory feature flags (migration state, dual-write on/off) are in Redis or Postgres.
- [ ] Kafka producer sets a key derived from the entity ID; consumer count ≤ partition count.
- [ ] `refresh_interval` and `replicas` are **not** flipped from a Deployment pod — only from a Job.
- [ ] Every write path uses `version_type: external` with a monotonic version.
- [ ] `/actuator/health/readiness` returns 503 during Flyway migration and ES index bootstrap.
- [ ] Admin endpoints (`/admin/*`) are auth-gated (Spring Security + admin role).
- [ ] Shutdown handler drains outbox poller / Kafka consumer before exit.
- [ ] Postgres replication slots are monitored (`pg_stat_replication`).
- [ ] ES cluster has ≥ 3 nodes, and shard sizing per the calculator.

If a POC is missing any of these, treat it as demo-grade and port carefully. That's what [TECHNICAL.md](./TECHNICAL.md)'s "acknowledged tech debt" sections are — the source list for this checklist.
