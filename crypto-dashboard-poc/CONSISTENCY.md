# CONSISTENCY — Scaling to many pods / VMs

The PoC is deliberately a **single JVM**. Everything that makes it fast — the
multicast `Sinks`, the `share()`d hot stream, the snapshot `ConcurrentHashMap`, the
subscriber `AtomicLong` — is **in-process state**. This document explains exactly
what breaks the moment you run more than one instance (whether that is
`kubectl scale deployment --replicas=N` or N VMs behind a load balancer), and the
architecture that fixes it.

> **The one sentence to remember:** the single-instance design has no shared state,
> so scaling it out doesn't "distribute the load" — it **forks the system into N
> independent, mutually-inconsistent copies**, each with its own upstream
> connections and its own version of the truth.

---

## 1. What horizontal scaling naively looks like

```
                         ┌───────── LB / Ingress (round-robin or sticky) ─────────┐
                         │                        │                        │
                    ┌────▼────┐              ┌────▼────┐              ┌────▼────┐
                    │  Pod A  │              │  Pod B  │              │  Pod C  │
                    │ 1 WS→BIN│              │ 1 WS→BIN│              │ 1 WS→BIN│   ← 3× upstream sockets
                    │ 1 WS→CB │              │ 1 WS→CB │              │ 1 WS→CB │
                    │ snap_A  │              │ snap_B  │              │ snap_C  │   ← 3 different snapshots
                    │ subs=1700              │ subs=1600│             │ subs=1700│  ← 3 partial counts
                    └────┬────┘              └────┬────┘              └────┬────┘
                clients 1..1700          clients 1701..3300       clients 3301..5000
```

Nothing is shared. That is the problem.

## 2. What actually breaks (per sub-problem)

| # | Symptom at N instances | Root cause | Severity |
|---|---|---|---|
| C1 | **N upstream WS per exchange** instead of 1. Binance/Coinbase see N connections from you → rate limits, throttling, or bans. | Each pod runs `@PostConstruct connect()` independently (tech debt **D2**). | 🔴 High — external, can get you blocked. |
| C2 | **Divergent snapshots.** A client on Pod A warm-starts with A's last-known prices; a client on Pod B sees B's. Ticks arrive at slightly different times per pod → visible disagreement on first paint. | Snapshot map is per-JVM (**D1**). | 🟠 Medium — user-visible inconsistency. |
| C3 | **Wrong/partial subscriber count.** `/subscribers` returns only *this pod's* count. Dashboards under-report by a factor of N. | `AtomicLong` is process-local (**D9**). | 🟡 Low — observability lie, not a data bug. |
| C4 | **Reconnect roulette / thundering herd.** When a pod dies or is rolled, all its SSE clients reconnect at once and the LB may land them on a *different* pod with a *different* snapshot — a visible "jump". A rolling deploy re-shuffles everyone. | SSE connection is bound to one pod's memory; no shared stream. | 🟠 Medium — spikes on deploy/scale events. |
| C5 | **Per-symbol ordering & dedup are per-pod only.** Each pod independently throttles/samples. Two clients on two pods can see different "latest" ticks at the same wall-clock instant. | `groupBy/sample` state is per-JVM. | 🟡 Low for display; 🔴 High if you later persist. |
| C6 | **Duplicate work & cost.** Every pod parses every tick from every exchange. Ingest+parse CPU is multiplied by N even though the *data* is identical. | No ingest/fan-out separation. | 🟡 Low–Medium (cost). |

**Key insight:** fan-**out** (P1) scales horizontally beautifully — more pods = more
SSE capacity, and that part is embarrassingly parallel. Fan-**in** (P2/P3) and
*shared truth* (P5) do **not** — they want to be centralized, not replicated.

## 3. The fix: split ingest from edge, put a bus between them

The moment you go multi-instance, separate the two roles the single JVM was
conflating:

```
        ┌─────────────── INGEST tier (small, fixed: 1 active + 1 standby) ──────────────┐
        │   Binance WS ─┐                                                                │
        │               ├─► normalize → PriceTick → publish to shared bus (topic/symbol)│
        │   Coinbase WS ┘                                     │                          │
        └─────────────────────────────────────────────────────┼──────────────────────────┘
                                                               │
                       ┌───────────────────── shared bus ──────┴───────────┐
                       │   Redis Pub/Sub  |  Kafka  |  NATS  |  Redis Stream │
                       │   + latest-snapshot store (Redis hash per symbol)   │
                       └───────┬───────────────┬───────────────┬────────────┘
                               │               │               │
                          ┌────▼────┐     ┌────▼────┐     ┌────▼────┐
                          │ Edge A  │     │ Edge B  │     │ Edge C  │   ← scale THIS tier freely
                          │ sub bus │     │ sub bus │     │ sub bus │
                          │ SSE out │     │ SSE out │     │ SSE out │
                          └────┬────┘     └────┬────┘     └────┬────┘
                          clients…        clients…        clients…
```

- **Ingest tier** owns the *only* upstream WS connections. Run it as a **singleton
  workload** (e.g. a K8s `Deployment` with `replicas: 1` + leader election, or a
  `StatefulSet`, or an active/standby pair). This fixes **C1** and **C6** — exactly
  one WS per exchange for the whole cluster.
- **Shared bus** carries normalized `PriceTick`s, partitioned/topic'd by symbol so
  ordering-per-symbol is preserved. Fixes **C5**.
- **Shared snapshot store** (a Redis hash `symbol → latest tick`, written by ingest)
  gives every edge pod the *same* warm-start. Fixes **C2** and **C4** (any pod a
  client lands on renders the same first paint).
- **Edge tier** is now stateless-per-client: subscribe to the bus, apply the same
  `filter → groupBy → sample → concat(snapshot,live) → heartbeat` pipeline, fan out
  over SSE. **This is the tier you scale** with `kubectl scale` / more VMs. Fixes **P1** at cluster scale.

## 4. Consistency model — what guarantees you actually get

Be explicit about semantics; "real-time dashboard" tolerates weaker guarantees than
"ledger".

| Property | This system's target | How it's achieved / why it's enough |
|---|---|---|
| **Delivery** | At-most-once *display*, best-effort | Drop-oldest on overflow (**D3**). Latest price supersedes stale; losing an intermediate tick is invisible on a price ticker. |
| **Ordering** | Per-symbol monotonic-ish | Bus partitioned by symbol; a single ingest writer per symbol. Cross-symbol order is not guaranteed and doesn't matter. |
| **Snapshot consistency** | Eventual, cluster-wide identical source | All edges read the *same* Redis snapshot; they converge within one tick. Not linearizable, and doesn't need to be. |
| **Cross-client consistency** | Eventual, bounded by `maxHz` window | Two clients may differ by up to one sample window (e.g. 100–500 ms). Acceptable for display; **not** acceptable if you build trading logic on it. |
| **Subscriber count** | Cluster-global, eventually consistent | Each edge reports its local count (Prometheus) → `sum()` in Grafana, or an atomic `INCR/DECR` on a Redis key. Fixes **C3**. |

> ⚠️ **Do not build order-matching, alerting thresholds, or billing on the SSE
> stream.** Its contract is "roughly the latest price, fast", not "every tick,
> exactly once, in order". Those use cases need Kafka with committed offsets and a
> persistent store — a different pipeline (tech debt **D3/D4**).

## 5. Kubernetes specifics

| Concern | Recommendation |
|---|---|
| **Ingest singleton** | `Deployment replicas:1` with a leader-election lease (`coordination.k8s.io` Lease), **or** a `StatefulSet` of 1, **or** active/standby with a `PodDisruptionBudget minAvailable:1`. Never let ingest scale with load. |
| **Edge autoscaling** | `HorizontalPodAutoscaler` on the edge `Deployment`. **Do not** scale on CPU (idle SSE connections are cheap) — scale on a **custom metric: active SSE connections per pod** (Prometheus Adapter → HPA). CPU-based HPA will never trigger and then fall over on memory/fd limits. |
| **Session affinity** | SSE is a single long GET; strict stickiness isn't required for correctness once snapshots are shared. Use `sessionAffinity: ClientIP` (or nothing) — but rely on the shared snapshot, not stickiness, for consistency. |
| **Graceful shutdown / rolling deploy** | Set `terminationGracePeriodSeconds` generously and drain: stop accepting new SSE, let existing ones ride the grace window or send a final `event: reconnect`. Clients auto-reconnect (EventSource does this) and land on another pod with the same snapshot → no visible jump (fixes **C4**). Use `maxSurge`/`maxUnavailable` to avoid mass simultaneous reconnects. |
| **Idle-timeout tuning** | Ingress/LB idle timeout **must exceed** the 15s heartbeat interval, or the LB kills streams between pings. Set LB idle timeout ≥ 60s and keep the heartbeat well under it. |
| **Connection limits** | An SSE pod holds thousands of fds. Raise the pod's `ulimit -n`, size Netty event-loop threads, and set memory requests/limits from the load-test heap numbers (< 200 MB for 5k conns is the PoC baseline). |
| **Probes** | Liveness: `/actuator/health`. Readiness: gate on "bus subscription established" so a pod doesn't take SSE traffic before it can serve ticks. |

## 6. VM / non-Kubernetes scaling

Same architecture, different mechanics:

- **Ingest singleton** = one designated VM (or an active/standby pair with a VIP /
  keepalived / a distributed lock in Redis/ZooKeeper for leader election). Do **not**
  run the ingest role on every VM.
- **Edge VMs** sit behind an L4/L7 load balancer (HAProxy/NGINX/ELB). Add VMs to
  scale fan-out. Set LB idle timeout > heartbeat, enable long-lived connection
  support (NGINX: `proxy_read_timeout`, disable buffering for `text/event-stream`).
- **Shared bus + snapshot store** = a Redis (or Kafka) cluster reachable by all VMs,
  exactly as in the K8s design.
- **Global metrics** = each VM exposes `/actuator/prometheus`; a central Prometheus
  scrapes all and `sum()`s the per-VM subscriber gauges.

## 7. Migration checklist (single-instance → cluster)

1. Extract ingest into its own deployable role; make it a cluster singleton (leader
   election). — fixes **C1, C6**
2. Introduce the bus (start with Redis Pub/Sub for simplicity; move to Kafka/NATS if
   you need replay/ordering guarantees). Partition by symbol. — fixes **C5**
3. Move the snapshot map to a shared store (Redis hash), written by ingest, read by
   edges on client connect. — fixes **C2, C4**
4. Make the subscriber count a Prometheus gauge and aggregate cluster-wide (or a
   Redis counter). — fixes **C3**
5. Turn the current monolith into the **edge** role: replace
   `aggregator.stream()`/`snapshot()` with "subscribe to bus" / "read Redis
   snapshot"; keep the `filter → groupBy → sample → concat → heartbeat` pipeline
   verbatim.
6. Add HPA on active-connections custom metric; tune LB idle timeout > heartbeat;
   add graceful drain.
7. Load-test the cluster the same way the single instance was tested — verify one WS
   per exchange cluster-wide and a correct cluster-global subscriber sum.

---

*See also:* [ISSUE.md](ISSUE.md) (the sub-problems P1–P5) and
[TECHNICAL.md](TECHNICAL.md) (the single-instance solution and its tech debt
D1–D9, several of which — D1, D2, D9 — are precisely the ones this document
resolves at scale).
