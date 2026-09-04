# CONSISTENCY — What changes when you scale to N pods / VMs

Read [ISSUE.md](ISSUE.md) and [TECHNICAL.md](TECHNICAL.md) first. This document is specifically about the *plurality* problem: what breaks, what changes shape, and what to do about it the moment there are two instances of this app instead of one.

> Kafka Streams *does* scale horizontally. But scaling a stateful streams app is not the same as scaling a stateless REST service. The state has an owner, the owner has a partition, and the partition is not on every pod. If you scale without understanding partition assignment, you get a slower single-instance system with worse failure modes — not a horizontally scaled one.

---

## 0. The mental model

The unit of scaling in Kafka Streams is not the pod — it's the **partition**.

- Every input topic has `N` partitions.
- Every Streams instance runs `stream.threads` threads (default `2` here, see [AppProperties.java:21](src/main/java/com/vndirect/kstreams/config/AppProperties.java)).
- Each **stream task** is `(sub-topology, partition)`. Tasks are the load-bearing unit; threads own tasks.
- Tasks are distributed across **all threads in all instances that share an `application.id`**.

Rule of thumb: **max useful parallelism = number of partitions**. You can run 100 pods against a 3-partition topic; 97 of them will be idle standbys or doing nothing.

For this POC:

- `orders.v1`, `payments.v1`, `enriched-orders.v1`, `completed-orders.v1`, etc. → 3 partitions (see [AppProperties.java:44](src/main/java/com/vndirect/kstreams/config/AppProperties.java)).
- With `num-stream-threads=2` per pod, one pod = 2 threads = 2 active tasks; a second pod adds 2 more threads but only 1 more active task can be scheduled (3 partitions total). The 4th thread is a standby or idle.

**Prescription for scaling this POC properly:** raise the partition count on the input topics *before* raising the pod count. A good target is `partitions ≥ peak_desired_pods × num_stream_threads`.

---

## 1. What Kafka Streams gives you for free

The moment you launch a second instance with the same `application.id`, all of this happens automatically:

- **Partition rebalance.** The Streams consumer group protocol assigns each partition to exactly one thread across the cluster. No manual sharding config.
- **Changelog-driven state migration.** If pod A owned partition 1's state store and pod B takes it over, pod B restores the state from the changelog topic on the broker.
- **Rebalance-safe processing.** In-flight records are committed before the partition moves.
- **Metadata for interactive queries.** `KafkaStreams.queryMetadataForKey(store, key, keySerde)` returns *which instance* owns a given key. (We do not use this yet — see §4.)

Everything below is what Kafka Streams does *not* give you for free.

---

## 2. The five scaling scenarios and what happens

### 2.1 Scenario: 1 pod → 2 pods, no config changes

**What happens:**

1. Second pod starts, joins the consumer group.
2. Broker triggers rebalance. Every existing task pauses briefly.
3. Some partitions are reassigned from pod A → pod B.
4. Pod B's stream threads **rebuild the reassigned partition's state stores** by replaying the changelog topic.
5. During the rebuild, that partition's data is **unavailable** to `/api/state/*` on either pod.

**How long is the rebuild?** Roughly proportional to state size / consumer throughput on the changelog. For this POC with tiny state, seconds. For a production app with GBs of state, **minutes**.

**What you actually see if you hit `/api/state/users/U-1001`:**

- On pod A, if that key's partition moved away: `IllegalStateException("KafkaStreams not RUNNING")` or a stale value.
- On pod B, until the rebuild finishes: `InvalidStateStoreException`.
- After the rebuild: correct on the pod that owns the key, **404 on the other pod** — because we don't route.

### 2.2 Scenario: pod crash / K8s pod eviction

**What happens:**

1. Broker detects the missing consumer within `session.timeout.ms` (default 45s).
2. Rebalance. Surviving pods take over the dead pod's partitions.
3. Full changelog replay to rebuild state on the takeover pod.
4. Latency spike on every downstream consumer of derived topics for the rebuild duration.

**How to shrink this window:** enable **standby replicas** via `num.standby.replicas`. A standby pod continues to consume the changelog even for partitions it does not actively own — so on failover it becomes primary in seconds, not minutes.

Add to `application.yml`:

```yaml
app:
  kafka:
    num-standby-replicas: 1   # not yet exposed — see debt below
```

Then wire it into [KafkaStreamsConfig.java](src/main/java/com/vndirect/kstreams/config/KafkaStreamsConfig.java) via `cfg.put(StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG, ...)`.

**Cost:** each standby is another RocksDB copy on disk, and extra broker fetch traffic per changelog. For a mission-critical low-latency service, worth it. For batch-y analytics, skip it.

### 2.3 Scenario: rolling deploy in K8s

**What happens with the default `RollingUpdate` strategy** (`maxUnavailable=1`, `maxSurge=1`):

1. K8s terminates one old pod.
2. Streams triggers a rebalance. Its tasks move to the surviving pods.
3. Surviving pods rebuild those partitions' state stores.
4. K8s starts a new pod on the new version.
5. **Second rebalance.** Tasks shuffle again. State stores rebuild *again* on the pods that just built them.
6. Repeat for every pod in the deployment.

**The pathological result:** every partition gets rebuilt twice per pod, per rollout. For a 3-pod deployment, that's 6× changelog reads per partition per rollout — a huge amount of broker load for zero net progress.

**Mitigations, in order of impact:**

1. **`static.membership` (`group.instance.id`)** — assign each pod a stable ID so the broker treats a restart as a temporary absence rather than a member leaving. Set `-Dgroup.instance.id=$POD_NAME` (from the K8s downward API). Combined with `session.timeout.ms` > pod restart time, this eliminates the extra rebalance entirely for a restart.
2. **`PreStop` hook** — call `KafkaStreams.close(Duration.ofSeconds(30))` before SIGTERM so the pod leaves the group cleanly and committed offsets are flushed.
3. **StatefulSet, not Deployment** — persistent volumes let a restarted pod pick up its own state dir instead of downloading it fresh from the changelog. `STATE_DIR_CONFIG` at [KafkaStreamsConfig.java:41](src/main/java/com/vndirect/kstreams/config/KafkaStreamsConfig.java) must point into the PV.
4. **Standby replicas** as above so the *takeover* pod is already warm.

**This POC ships with none of these on** — see debt below.

### 2.4 Scenario: VMs (autoscaling group / VMSS)

VMs behave the same as K8s pods with a big caveat: **VM boot is slower than pod boot**. A rebalance triggered by a new VM booting can take a few minutes; the extra latency is dominated by JVM cold start, not by Kafka.

Practical differences vs K8s:

| Concern | K8s | VMs |
|---|---|---|
| Instance identity | Downward API → `POD_NAME` | Metadata service → `instance-id` |
| Persistent state | PVC on a StatefulSet | Attach EBS/managed disk to VM |
| Health check | Liveness/readiness on `/actuator/health` | ALB/Nlb health check on `/actuator/health` |
| Rebalance cadence | Fast rollouts, many rebalances | Slow rollouts, fewer but longer rebalances |
| Failure blast radius | Node failure ≈ 1 pod | AZ failure ≈ many VMs |

The advice above (`group.instance.id`, standby replicas, persistent state, warm shutdown) applies verbatim.

### 2.5 Scenario: scale to N without raising partition count

**What happens:** nothing. Adding a 4th pod against a 3-partition topic gives you a hot-idle pod. Its threads either sit idle (no standbys configured) or run standby tasks (if `num.standby.replicas > 0`).

**Prescription:** repartitioning input topics online is invasive. Plan for it up front. A safe rule: **partitions = 3 × expected peak instances** so scale-up has headroom.

---

## 3. Consistency model — what "consistent" even means here

Kafka Streams gives you two different consistency stories depending on `processing.guarantee`:

### 3.1 `at_least_once` (this POC's default)

- Every record is processed **≥ 1 time**.
- A crash between "produce output" and "commit input offset" → replay → **duplicate output**.
- **Every downstream consumer of `enriched-orders.v1`, `completed-orders.v1`, etc. must be idempotent** — treat this as an API contract, not a nice-to-have.

Concrete implications for this POC's outputs:

| Topic | Effect of a duplicate |
|---|---|
| `enriched-orders.v1` | Downstream sees the order twice; each observer needs `orderId`-level dedupe |
| `completed-orders.v1` | Payment latency gets emitted twice; dashboards over-count |
| `category-revenue.v1` | Windowed aggregate is *idempotent by key (window, category)* — safe |
| `user-order-counts.v1` | Same — windowed idempotent by (window, userId) |
| `user-sessions.v1` | Same, but late merges make dedup non-trivial |
| `streams.dlq.v1` | Poison-pill can appear twice; DLQ triage tools should tolerate this |

### 3.2 `exactly_once_v2`

Flip [AppProperties.java:25](src/main/java/com/vndirect/kstreams/config/AppProperties.java) to `exactly_once_v2` and the story becomes:

- Streams uses Kafka transactions to atomically write output records + commit input offsets.
- The trade-offs: **higher latency** (commit is a transaction commit, not a plain produce), **higher broker load** (transaction coordinator traffic), and **transactional replication factor** must be ≥3 on the broker side (`transaction.state.log.replication.factor`).

**When to flip it:** when a downstream consumer *cannot* be made idempotent — e.g. a ledger that debits an account per event. If the downstream is a search index or an aggregate dashboard, `at_least_once` + idempotent consumer is usually cheaper.

### 3.3 What the aggregate topics actually guarantee

`user-sessions.v1` and friends are **compacted-friendly** in the (window, key) sense — each windowed update supersedes the previous one for the same key. If a consumer reads them as a KTable (not a KStream), they see the correct latest aggregate even under `at_least_once`.

This is why the topology emits `withWindow(start, end)` stamped values — it makes the (window-start, window-end, key) triple usable as a natural dedup key.

---

## 4. Interactive Queries under horizontal scale — the big footgun

`GET /api/state/users/U-1001` on this POC today:

1. Hits whichever pod the load balancer picked.
2. Calls `factoryBean.getKafkaStreams().store(...)`.
3. Returns the value **if this pod owns the partition for `U-1001`**, else returns `null` → HTTP 404.

At 1 pod, this always works. At N pods, it's correct **1/N of the time on average**.

The fix is well-known but not implemented:

```java
KeyQueryMetadata md = streams.queryMetadataForKey(
        OrderEnrichmentTopology.USERS_STORE, userId, Serdes.String().serializer());

if (md.activeHost().equals(myHostInfo)) {
    return localLookup(userId);      // this pod owns it
} else {
    return httpForward(md.activeHost(), userId);   // hop to the pod that does
}
```

Requirements to implement it in this repo:

1. Set `StreamsConfig.APPLICATION_SERVER_CONFIG = "${POD_IP}:8080"` in [KafkaStreamsConfig.java](src/main/java/com/vndirect/kstreams/config/KafkaStreamsConfig.java) so each instance advertises itself.
2. Replace the direct `store.get(...)` in [StateStoreController.java](src/main/java/com/vndirect/kstreams/api/StateStoreController.java) with a `queryMetadataForKey` lookup + `RestTemplate`/`WebClient` hop.
3. On the receiving pod, add a `X-Kstreams-Forwarded: 1` header check to prevent forwarding loops during rebalance.

Windowed stores (`fetchAll`) are trickier — the correct answer requires **fan-out to all instances** and stitching the results. `KafkaStreams.streamsMetadataForStore` returns the list of hosts.

**Until this is done, do not deploy `/api/state/*` behind a normal LB with more than one replica.** Either:

- Keep 1 replica for read; scale write independently (a supported pattern), or
- Restrict `/api/state/*` to internal callers who tolerate 404s, or
- Implement the routing above.

---

## 5. What consistency looks like end-to-end (a walk-through)

Consider a single order placed at T0:

| Time | Event | Consistency state |
|---|---|---|
| T0 | Producer publishes `orders.v1` record for `orderId=O-42`, partition = `hash(O-42) % 3 = 1` | Only broker has it |
| T0 + ε | Pod that owns task `(enrichment, p1)` reads it | In-flight in pod A |
| T0 + 2ε | Enriched with product P (GlobalKTable lookup — local, always available) | Enriched value not yet committed |
| T0 + 3ε | Aggregation topology re-keys by `category=EQUITY`; that key hashes to partition 0 | Repartition record traveling to pod B (which owns p0) |
| T0 + 4ε | Pod B updates `category-revenue-store` window state | Aggregate visible in pod B's local store only |
| T0 + `commit.interval.ms` (1s) | Commit: input offsets + changelog + output all flushed | Now durably recorded; on `at_least_once`, could be replayed |
| T0 + 30s | K8s evicts pod B | Rebalance; task moves to pod C |
| T0 + 30s + rebuild | Pod C replays changelog for `(category-revenue-store, p0)` | Same aggregate value re-materialized on pod C |
| T0 + ??? | Client calls `GET /api/state/category-revenue?windowMinutes=10` on pod A | Pod A does NOT own p0 → windowed IQ returns partial results (fan-out not implemented) |

The last row is the punchline: **the system is internally consistent (state is correctly rebuilt on takeover), but the read API is not aware of the topology of ownership**. Fix the read layer before you scale.

---

## 6. Checklist: what to change before running >1 pod

1. [ ] Raise input-topic partition count to your target parallelism (see [AppProperties.java:44](src/main/java/com/vndirect/kstreams/config/AppProperties.java)).
2. [ ] Set `StreamsConfig.APPLICATION_SERVER_CONFIG` per pod (POD_IP + port).
3. [ ] Set `group.instance.id` per pod (K8s downward API).
4. [ ] Use a StatefulSet with a PVC mounted at `state.dir`, not a Deployment on ephemeral storage.
5. [ ] Set `num.standby.replicas ≥ 1`.
6. [ ] Implement IQ routing in [StateStoreController.java](src/main/java/com/vndirect/kstreams/api/StateStoreController.java), or restrict its exposure to a single-pod read path.
7. [ ] Add `preStop: kafka-streams-close` hook so pods leave the group cleanly.
8. [ ] Set K8s `terminationGracePeriodSeconds` ≥ Streams close timeout ≥ largest inflight batch time.
9. [ ] Confirm `session.timeout.ms` > pod restart time (typical: 45s default is fine; JVM restart is faster).
10. [ ] Decide `at_least_once` vs `exactly_once_v2` **based on downstream idempotency**, not "we want strong consistency".
11. [ ] Actuator: put `/actuator/env` and `/actuator/configprops` behind auth or drop them entirely.
12. [ ] Add a synthetic canary that pushes an order + payment and asserts a `CompletedOrder` appears in `< X seconds`.

Each of those is a one- to five-line change. None of them are refactors. But every one of them is load-bearing the moment there are two pods.
