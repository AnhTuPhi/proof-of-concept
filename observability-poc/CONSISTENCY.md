# CONSISTENCY.md — what breaks when you scale out

This POC runs **one replica of each service and one collector**. The moment you scale —
K8s `replicas: N`, an HPA, or several VMs behind a load balancer — a set of *consistency*
problems appears that are invisible at N=1. This document walks each signal type and
each infra component through "what changes when there are many of me."

> TL;DR — **traces** survive scaling for free (they're self-describing per request).
> **Metrics** and **tail-sampling** do **not**: they need topology changes (a gateway
> collector tier, a load-balancing exporter, and a pull model that expects churn).

---

## The scaling picture

```
                       ┌── order-service pod 1 ─┐
   HPA scales these ──►├── order-service pod 2 ─┤ each with an OTel SDK
                       └── order-service pod N ─┘        │ OTLP
                                                         ▼
                          ┌─────────────────────────────────────────┐
        AGENT tier        │  collector DaemonSet (1 per node/VM)      │  ← stateless, cheap
        (local, per node) │  receive → batch → forward                │
                          └───────────────────┬───────────────────────┘
                                              │ OTLP (load-balanced by trace_id)
                          ┌───────────────────▼───────────────────────┐
        GATEWAY tier      │  collector Deployment (M replicas)         │  ← STATEFUL for
        (central pool)    │  tail_sampling / spanmetrics / fan-out     │     tail-sampling
                          └──────┬──────────────┬──────────────┬───────┘
                                 ▼              ▼              ▼
                              Jaeger/Tempo   Prometheus       Loki
                              (HA + object storage behind each)
```

The single POC collector is really **two roles fused into one**. Scaling forces you to
split them: a cheap stateless **agent** near each workload, and a **gateway** pool that
does the stateful work.

---

## 1. Traces — mostly fine, one sharp edge

**Why replicas are OK.** A trace is reassembled from spans that each carry their own
`trace_id` + `parent_span_id` (W3C Trace Context). It does **not** matter which
`order-service` pod handled the request or which collector a span passed through —
Jaeger stitches the tree by ID on arrival. Horizontal scaling of *services* is
transparent to tracing.

**The sharp edge: tail-based sampling.** The realistic production fix for
"we can't keep every trace" (tech-debt #1 in [TECHNICAL.md](TECHNICAL.md)) is
**tail sampling** — decide keep/drop *after* seeing the whole trace (e.g. keep all
errors and anything > 500 ms). That decision requires **all spans of one trace to reach
the same collector instance**. With M gateway collectors and naïve round-robin, a
trace's spans scatter across instances and each sees only a fragment → wrong decisions,
broken traces.

**Fix.** Two-tier topology with a **load-balancing exporter** on the agent tier keyed
on `trace_id`, so every span of a trace is routed to the *same* gateway collector.
Only then can `tail_sampling` run correctly.

```yaml
# agent-tier collector — route by trace so gateways can tail-sample
exporters:
  loadbalancing:
    routing_key: traceID
    protocol: { otlp: { tls: { insecure: true } } }
    resolver:
      dns: { hostname: otel-gateway.observability.svc.cluster.local }
```

---

## 2. Metrics — the real consistency problem

Metrics are where "scale the pods" quietly corrupts your data. Three distinct issues:

### 2a. Counter resets on pod churn
Prometheus counters are **monotonic per process**. When an HPA kills a pod or a
rollout replaces one, its counter vanishes and the replacement starts at 0.
`orders_created_total` looks like it went *backwards*.
**This is expected** — always aggregate with **`rate()` / `increase()`**, never raw
counter values, and always keep the `service.instance.id` resource attribute so each
replica is a distinct series Prometheus can reset-detect independently.

### 2b. Scrape target discovery
The POC hard-codes `targets: ['otel-collector:8889']`
([prometheus.yml](prometheus/prometheus.yml)). With M collector replicas behind a
Service, a single DNS name load-balances scrapes → Prometheus scrapes a *random*
collector each interval and sees flapping partial data.
**Fix.** Use **Prometheus service discovery** (`kubernetes_sd_configs`) to scrape
*every* collector pod as its own target, or push to a remote-write endpoint. Each
collector must expose a stable `service.instance.id` so its series don't collide.

### 2c. Aggregation temporality & double-counting
If two collectors both export the same metric name for the same resource, and both are
scraped, you double count. The gateway tier must either (a) be the *only* thing
Prometheus scrapes, with agents forwarding via OTLP, or (b) use the collector's
`spanmetrics`/aggregation only at **one** tier. Decide **where aggregation happens** and
make it exactly one place.

> **Rule of thumb:** metrics want a *pull model that expects churn* (SD + `rate()`),
> or a *push model with a single aggregation point*. Never both, never neither.

---

## 3. Logs — cheap to scale, watch cardinality & ordering

**Why replicas are OK.** Each log record is independent and carries `trace_id` +
`service.name` + `service.instance.id`. More pods = more log volume, not a correctness
problem — Loki ingests them all and correlation still works by `trace_id`.

**Two things that bite at scale:**

- **Label cardinality.** Loki indexes by **labels**, not full text. Keep labels to
  low-cardinality dims (`service.name`, `env`, `level`). Putting `trace_id` or
  `order_id` in a *label* (vs. the log body) explodes the index. In the POC `trace_id`
  lives in the JSON body and is matched via a derived-field regex — keep it that way.
- **Loki's own consistency.** The POC runs `replication_factor: 1`, in-memory ring,
  local `filesystem` ([loki-config.yaml](loki/loki-config.yaml)). That is single-node
  only. Scaled Loki needs the **ring on a shared KV store** (memberlist/Consul),
  `replication_factor: 3`, and **object storage** (S3/GCS) so any ingester/querier
  replica sees the same data.

---

## 4. The Collector — from one container to a topology

| Concern | POC (N=1) | Scaled |
|---|---|---|
| Placement | one shared container | **agent** DaemonSet (per node) + **gateway** Deployment (pool) |
| State | fully stateless | agents stateless; **gateway stateful** for tail-sampling |
| Routing | direct | agent → gateway via **loadbalancing exporter** keyed on `traceID` |
| Backpressure | `memory_limiter` on one box | per-tier `memory_limiter`; gateway HPA on queue depth |
| Failure blast radius | total outage | node-local agent loss ≪ gateway pool with N>1 |

The `memory_limiter`, `batch`, and `resource` processors already in
[otel-collector/config.yaml](otel-collector/config.yaml) carry over unchanged — they're
per-instance and scale horizontally. The *new* stateful piece is `tail_sampling`, which
is exactly why the gateway tier can't be casually autoscaled: **scaling the gateway pool
reshuffles which traces land where.** Scale it deliberately, drain gracefully, and keep
the `loadbalancing` resolver in sync with pool membership.

---

## 5. VM scaling vs. K8s pod scaling — the differences that matter

The consistency issues above are identical in spirit on both, but the *mechanics*
differ:

| Dimension | K8s (scale pods / HPA) | VMs (scale instances / ASG) |
|---|---|---|
| Discovery of new replicas | automatic via Service + `kubernetes_sd_configs` | manual/DNS/Consul — Prometheus SD must be wired to the ASG |
| Collector agent placement | DaemonSet = exactly one per node, free | run a collector as a **systemd** service on every VM image |
| `service.instance.id` | pod name/UID — stable & unique per replica | must be injected (hostname/instance-id) or metrics series collide |
| Graceful drain (protects tail-sampling) | `preStop` hook + `terminationGracePeriod` to flush the batch | ASG lifecycle hook + SIGTERM handler (the SDK already flushes on SIGTERM — [telemetry.ts:49](services/order-service/src/telemetry.ts)) |
| Autoscale signal | HPA on CPU / queue depth | ASG on CPU / custom CloudWatch metric |
| Ephemerality | pods churn *constantly* — counter resets are the norm | VMs churn less, so resets are rarer but **not** absent — still use `rate()` |

**Key takeaway for both:** the two things that must be *deliberately engineered* when
you scale — regardless of K8s vs. VM — are (1) **route a trace's spans to one collector**
so tail-sampling is correct, and (2) **give every replica a stable unique
`service.instance.id`** and aggregate metrics with `rate()` so pod/VM churn reads as
churn, not as data corruption. Everything else (traces, logs) scales for free.

---

## Minimal checklist to make this POC scale-safe

- [ ] Split collector into **agent (DaemonSet/systemd)** + **gateway (Deployment/pool)**.
- [ ] Add **`loadbalancing` exporter keyed on `traceID`** on the agent tier.
- [ ] Move **`tail_sampling`** to the gateway tier; keep errors + slow traces.
- [ ] Set a unique **`service.instance.id`** resource attribute per replica.
- [ ] Switch Prometheus to **service discovery**; dashboard everything via `rate()`.
- [ ] Pick **one** aggregation point for metrics (no double counting).
- [ ] Loki/Prometheus/Jaeger(Tempo) on **object storage + `replication_factor ≥ 3`**.
- [ ] Guard **label/attribute cardinality** (no `order_id` as a label).
- [ ] Add **readiness/liveness probes** and **graceful drain** on every tier.
