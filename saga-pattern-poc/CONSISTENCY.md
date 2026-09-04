# CONSISTENCY.md — Staying correct when you scale out (K8s pods / multiple VMs)

> Prerequisite reading: [ISSUE.md](ISSUE.md) (the invariants) and
> [TECHNICAL.md](TECHNICAL.md) (the mechanisms). This document answers one question:
>
> **When you run N replicas of every service — `kubectl scale deploy/... --replicas=N`, an
> HPA reacting to load, or just several VMs behind a load balancer — do the all-or-nothing
> guarantees still hold?**
>
> Short answer: **yes, by design**, provided you don't break the three assumptions below.
> The rest of this file is the "why", the failure modes to watch, and the config that keeps
> it true.

---

## 0. The three assumptions everything rests on

Horizontal scaling is safe here because the correctness never depended on "one instance".
It depends on:

1. **Idempotent effects.** Every handler/activity produces the same result if run twice.
   (`processed_events` table in choreography; business-id idempotency in activities.)
2. **Single-writer-per-unit ordering.** All events for one `sagaId` are processed by one
   consumer at a time (Kafka partition ownership); one workflow runs single-threaded.
3. **A lock around shared mutable rows.** Concurrent sagas serialize on the `stock` row via
   `SELECT … FOR UPDATE` + `@Version`.

Scale breaks correctness only if you violate one of these — e.g. add an idempotency-skipping
fast path, repartition without care, or drop the row lock. Keep them and N replicas behave
like one, only faster.

---

## 1. Choreography under scale

### What actually parallelizes
Every choreography service is a **Kafka consumer in a named group**
(`spring.kafka.consumer.group-id`). Kafka's group protocol assigns each **partition of
`saga.events` to exactly one consumer instance** in the group. So:

```
saga.events, 12 partitions
        │
        ├─ partition 0..3  ─▶ payment-service pod A
        ├─ partition 4..7  ─▶ payment-service pod B
        └─ partition 8..11 ─▶ payment-service pod C     (group "payment-svc")
```

- Add a pod → Kafka **rebalances** and hands it some partitions. Throughput rises.
- Kill a pod → its partitions are reassigned to survivors; uncommitted offsets are
  redelivered (idempotency absorbs the replay).
- **Ceiling:** useful parallelism per service = **number of partitions**. More pods than
  partitions just leaves the extras idle. **So partition count is your real scale knob** —
  provision it generously up front (repartitioning a live topic is disruptive).

### Why ordering survives scaling
All events for one saga share the same key (`sagaId`) → same partition → one owner at a
time → in-order. Two *different* sagas may land on different pods and run fully in parallel.
That is exactly what we want: **serialize within a saga, parallelize across sagas.**

### The failure modes to watch when scaling out

| Failure mode | Cause under scale | Mitigation (in repo / to add) |
|---|---|---|
| **Double processing during rebalance** | A pod is mid-handle when partitions move; the new owner reprocesses | ✅ `processed_events` idempotency + manual `ack` after DB commit |
| **Duplicate side effects** | At-least-once redelivery multiplied by more consumers | ✅ every handler checks `eventId` first |
| **Stock oversubscription** | Two pods reserve the same SKU concurrently | ✅ `findForUpdate` pessimistic lock + `@Version` |
| **Lost event stalls a saga** | Dual-write gap (no outbox, [TECHNICAL.md#tech-debt](TECHNICAL.md#tech-debt-to-acknowledge) #1) — more pods = more broker connections = more windows | ⚠️ **add transactional outbox** before scaling for real |
| **`processed_events` unbounded growth** | More throughput = more rows | ⚠️ add TTL / periodic purge of old event ids |
| **Poison message parks a partition** | One bad record retried on every pod that owns it | ✅ `DefaultErrorHandler` → `saga.events.DLT` after backoff |

### K8s specifics for choreography
- **Readiness probe** on Actuator `/health`: only join the consumer group when DB + Kafka
  connections are up, so a starting pod doesn't get partitions it can't serve.
- **`preStop` + graceful shutdown**: the services set
  `spring.lifecycle.timeout-per-shutdown-phase=30s`. On `SIGTERM`, finish in-flight records
  and commit offsets *before* leaving the group, so a rolling deploy doesn't strand work.
  Set `terminationGracePeriodSeconds` ≥ that value.
- **Do not** rely on sticky sessions or pod identity — consumers are interchangeable; that's
  the whole point. A `Deployment` (not `StatefulSet`) is correct here.
- **HPA** on consumer lag (via KEDA `kafka` scaler) is the right signal, not CPU — you scale
  when events pile up, and you can't usefully exceed partition count.

---

## 2. Orchestration (Temporal) under scale

Temporal splits into two independently scalable tiers, and this is its big advantage under
scaling: **application state is not in your pods.**

### Tier A — your worker pods (stateless)
`orchestrator-service` pods run **Temporal workers** that poll a task queue
(`ORDER_SAGA_TASK_QUEUE`) for workflow tasks and activity tasks.

- Workers are **stateless and interchangeable**. Add pods → more pollers → more workflows
  and activities execute concurrently. Remove pods → survivors keep polling.
- A worker crash mid-workflow is a **non-event**: the workflow's authoritative state is the
  **event history in the Temporal server**, not in the pod. Another worker replays the
  history and continues at the next un-run step. This is the property choreography has to
  reconstruct manually with offsets + idempotency.
- **A single workflow execution is single-threaded and single-owner at any instant** — even
  with 100 worker pods, one order's saga is never run by two workers simultaneously.
  Ordering and "run compensations in reverse" hold for free at any scale.

### Tier B — the Temporal server + its datastore
This is the **stateful heart** and the real scaling concern:

- The POC uses `temporalio/auto-setup` on **Postgres** — a single-node dev image. Fine for
  a laptop, **not** for production scale or HA.
- To scale for real: run the Temporal server as its own K8s deployment (frontend / history /
  matching / worker services scale independently) backed by **Cassandra** (or a managed
  **Temporal Cloud**). The datastore, not your app pods, becomes the throughput ceiling.
- **Task-queue partitioning** and history-shard count govern server-side parallelism; size
  them for your workflow rate.

### Failure modes to watch when scaling Temporal

| Failure mode | Cause under scale | Mitigation |
|---|---|---|
| **Double activity execution** | At-least-once activity dispatch across more workers | ✅ idempotent activities keyed on `orderId`/`paymentId` |
| **Non-deterministic replay crash** | Two worker builds with different workflow logic replaying one history | ⚠️ use **Worker Versioning** / build ids; deploy workflow code changes carefully |
| **Server datastore is the bottleneck** | Postgres dev image can't take the load | ⚠️ move to Cassandra / Temporal Cloud |
| **Activity DB contention** | Many workers hitting the same `stock` row | ✅ pessimistic lock + `@Version` inside the activity |

### K8s specifics for orchestration
- Worker pods: `Deployment`, readiness on `/health`, graceful `SIGTERM` — the Temporal SDK
  **drains in-flight tasks** on shutdown, so rolling deploys don't lose work.
- Scale workers on task-queue backlog / schedule-to-start latency (Temporal exposes these
  metrics), not raw CPU.
- Keep the Temporal **server** deployment separate from worker deployments so you can scale
  and upgrade them independently.

---

## 3. The rule that makes scaling safe (both flavors)

> **Idempotency + single-writer-per-unit + row locking = N replicas behave like 1, faster.**

| Concern | Choreography guarantee | Orchestration guarantee |
|---|---|---|
| One saga's steps stay ordered | Partition-per-`sagaId`, one consumer owns it | Workflow runs single-threaded, one owner |
| Duplicates don't double-charge | `processed_events(event_id)` | idempotent activities on business id |
| Concurrent sagas don't corrupt shared stock | `FOR UPDATE` + `@Version` | same, inside activity |
| A dead pod doesn't lose the saga | Uncommitted offset redelivered | history replayed by another worker |
| Where's the app state | In each service's DB + Kafka | In the Temporal server (off your pods) |

**Practical scale knobs**
- Choreography: **partition count of `saga.events`** (hard ceiling) → then pods, autoscaled
  on **consumer lag** (KEDA).
- Orchestration: **worker pod count** (cheap, stateless) → but ultimately bounded by the
  **Temporal server datastore**; scale that tier deliberately.

**Before you scale for real, close these first** (see [TECHNICAL.md](TECHNICAL.md#tech-debt-to-acknowledge)):
1. **Transactional outbox** — otherwise more broker connections = more dual-write loss
   windows, and the lost-event stall gets *more* likely as you scale, not less.
2. **Stuck-saga sweeper / saga timeout** in choreography — a scaled fleet produces more
   sagas that can strand if an event is lost.
3. **`processed_events` retention** — bound the table so idempotency storage doesn't grow
   without limit.
4. **Temporal server on an HA datastore** — the dev Postgres image is a single point of
   failure and a hard throughput cap.

---

## 4. TL;DR

- **Both flavors scale horizontally without changing the code** because correctness lives in
  idempotency + locking + single-writer ordering, not in "there is only one instance".
- **Choreography** scales by adding consumer pods up to the **partition count**; watch the
  dual-write gap and unbounded idempotency tables.
- **Orchestration** scales worker pods **trivially** (they're stateless) but pushes the real
  scaling problem into the **Temporal server's datastore**, which you must run HA.
- The moment you turn on autoscaling, the **transactional-outbox gap** stops being academic —
  fix it first.
