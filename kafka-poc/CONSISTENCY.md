# CONSISTENCY.md — Scaling these apps across K8s pods and VMs

Everything in this repo runs as a single process on a laptop. Production runs **N
replicas** of each module — Kubernetes pods or VMs behind an autoscaler. Horizontal
scaling is where Kafka's guarantees either hold or quietly break, because the unit of
parallelism (the partition) and the unit of identity (consumer group member,
transactional id, static instance id) suddenly matter.

This document explains **what changes when you add replicas**, per pattern, and how to
keep the guarantees intact.

---

## The one law that governs all scaling

> **Partition count is the ceiling on consumer parallelism within a group.**

A partition is consumed by **exactly one** member of a group at a time. So:

- `replicas ≤ partitions` → each replica owns ≥1 partition. Good.
- `replicas == partitions` → maximum useful parallelism. One partition each.
- `replicas > partitions` → the extra pods sit **idle**. The HPA will happily scale to
  10 pods against a 6-partition topic and 4 do nothing. Scale the topic, not just the pods.

The docker-compose default here is `KAFKA_NUM_PARTITIONS: 6`, so any group tops out at 6
active consumers. Provision partitions for your *peak* replica count up front —
increasing partitions later **breaks key→partition affinity** and therefore per-key
ordering (see the ordering section below).

**Corollary for ordering:** ordering is guaranteed only *within a partition*. Any pattern
that needs per-entity ordering (a saga per order, a projection per order) must key by that
entity id so all its events share a partition. Scaling replicas never breaks this; scaling
*partitions* does.

---

## Identity: the thing laptops hide and clusters expose

Three modules depend on a **stable per-replica identity**. On a laptop there's one process,
so identity is trivial. Across pods it is the central scaling problem.

| Module | Identity needed | Why | Failure if wrong |
|---|---|---|---|
| 02 transactions | `transactional.id` | zombie fencing after crash | two live producers with the same id fence each other into a crash loop; different ids per restart defeat fencing |
| 05 rebalancing | `group.instance.id` (static membership) | skip rebalance on short restarts | duplicate ids → members fence each other; random ids → every restart rebalances |
| any consumer | `group.id` | defines the group that shares partitions | accidental unique per-pod group.id → every pod gets **all** partitions (fan-out, not scale-out) |

### Deployment vs StatefulSet

- **Deployment** gives pods **random** names (`app-7d9f-abc12`). Fine for **stateless
  consumers** (modules 03, 04, 08) where any replica can own any partition and identity is
  disposable. Use `group.id` = app name; let Kafka assign partitions.
- **StatefulSet** gives **stable ordinal** names (`app-0`, `app-1`, …). Required for modules
  that need stable identity:
  - `transactional.id = "txn-rpw-" + HOSTNAME` → `txn-rpw-app-0`. Stable across restarts of
    that ordinal, unique across ordinals. This is exactly what
    `ReadProcessWriteService` derives from `HOSTNAME`.
  - `group.instance.id = HOSTNAME + "-bp"` → `app-0-bp`. `BackpressureConsumer` does this.

> **The trap:** running module 02 or 05 as a plain **Deployment**. `HOSTNAME` becomes a
> random string that changes every restart, so `transactional.id` / `group.instance.id`
> churn — you lose zombie fencing and static membership entirely. Use a StatefulSet, or
> inject a stable id from the Downward API / a config-generated ordinal.

```yaml
# StatefulSet essentials for modules 02 and 05
apiVersion: apps/v1
kind: StatefulSet
spec:
  serviceName: txn-rpw
  replicas: 3            # keep <= partition count of the input topic
  template:
    spec:
      containers:
        - name: app
          env:
            - name: HOSTNAME               # stable: app-0, app-1, app-2
              valueFrom:
                fieldRef: { fieldPath: metadata.name }
```

---

## Rolling deploys: why they hurt and how these modules survive

A rolling deploy restarts every replica. Naively, each restart triggers a **rebalance**:
partitions are revoked from the whole group, reassigned, and consumers pause. With the
*eager* assignor this is stop-the-world for seconds, times the number of pods.

Module 05 fixes this and it scales as follows:

1. **Static membership** (`group.instance.id`): a pod that restarts and rejoins within
   `session.timeout.ms` (30s here) is recognized as the *same* member — **no rebalance**.
   Set your pod `terminationGracePeriodSeconds` + image pull + startup **under**
   `session.timeout.ms` so restarts stay invisible. If a rollout is slower than that, raise
   `session.timeout.ms` (at the cost of slower dead-pod detection).
2. **Cooperative-sticky assignor**: when a rebalance *is* unavoidable (scale-up/down, a
   genuinely new member), only the partitions that actually move are revoked — the rest keep
   processing. Pause time scales with *partitions moved*, not *total partitions*.
3. **`preStop` + commit on revoke**: `onPartitionsRevoked` calls `commitSync` before losing a
   partition, so the pod taking over doesn't reprocess. Add a `preStop` hook / graceful
   shutdown so in-flight work drains.

**Scale-down / HPA-down caveat:** removing a pod always reassigns its partitions to survivors.
Cooperative-sticky keeps this cheap, but design handlers to be **idempotent** (module 03)
because the survivor may reprocess the last uncommitted batch.

---

## Per-module behavior when you run N replicas

| Module | Replicas > 1 behavior | What keeps it consistent |
|---|---|---|
| **01 producer** | Each pod is an independent idempotent producer. No coordination needed. Dedup is per-session, so a pod restart can re-emit — consumers dedupe. | idempotent producer + consumer-side dedup |
| **02 transactions** | Partitions split across pods; each needs a **unique stable** `transactional.id`. Fencing handles zombies. | StatefulSet ordinal → `transactional.id` |
| **03 offsets** | Stateless scale-out. Partitions divide among pods; each commits its own. | manual commit after work + `IDEMPOTENT_AFTER` dedup table (shared DB) |
| **04 DLQ** | Retry/DLQ topics are shared; any pod can process any retry. | idempotent handlers; DLQ replay is at-least-once |
| **05 rebalancing** | The whole point — N pods share partitions with minimal-pause rebalances. | static membership + cooperative-sticky |
| **06 outbox** | **Many pollers run in parallel safely** via `FOR UPDATE SKIP LOCKED` — each grabs a disjoint row batch. No leader election needed. | `SKIP LOCKED` row leasing in Oracle |
| **07 saga** | Each service scales independently; a saga's events stay ordered because they're keyed by `orderId`. | key = orderId → one partition per saga |
| **08 CQRS** | N projectors share partitions. Same-order events land on the same partition → same pod → no cross-pod race for that order. | key = orderId + `occurredAt` staleness guard |
| **09/10 streams** | Streams scales by **stream threads / instances up to partition count**; state stores are sharded per partition. Use `num.standby.replicas≥1` so a lost pod's state is warm elsewhere. | partition-sharded state + standby replicas |
| **11 avro** | Stateless; every pod uses the same registry. Schema id cached per pod. | shared Schema Registry |
| **12 CDC** | Run Connect in **distributed mode, ≥2 workers**; tasks (not pods) are the parallelism unit and rebalance across workers. **Only one Debezium task** reads the Oracle redo log — it does not scale horizontally. | Connect distributed mode; source is single-task by nature |

Two structural facts fall out of that table:

- **Stateless consumers (03, 04, 08, 11) scale trivially** — add pods up to partition count.
- **Stateful / identity-bound components (02, 05, 09/10 state, 12 source) need care** —
  StatefulSets, standby replicas, or "single active reader" reality.

---

## Shared state is the other consistency boundary

Horizontal scaling of the *apps* is only half the story — several modules lean on **shared
external stores**, and those must scale/behave correctly too:

- **Oracle (modules 03, 06, 07)** is the consistency anchor. `SKIP LOCKED` and unique
  constraints do the cross-pod coordination so the app tier needs no leader election. But the
  DB is now a shared bottleneck and a single point of failure — size the connection pool per
  pod × replica count, and the dedup/outbox tables need **purge jobs** or they grow forever.
- **Elasticsearch (module 08)** must tolerate concurrent upserts to the same doc from
  different pods. The read-then-write in `patchStatus` is a race under scale — move to scripted
  updates or external versioning before running many projector replicas.
- **Schema Registry (module 11)** is shared and cache-friendly; scale it for availability, not
  throughput.

---

## A practical scaling checklist

Before you bump `replicas`:

1. **`replicas ≤ topic partitions`** for every input topic the module consumes. If not, add
   partitions first (and accept the key-affinity reshuffle, ideally at a quiet time).
2. **Pick the workload the right controller.** Stateless consumer → Deployment. Needs stable
   identity (02, 05) or local state (09, 10) → StatefulSet.
3. **Inject stable identity** (`HOSTNAME`/ordinal) for `transactional.id` and
   `group.instance.id`; never let them be random per restart.
4. **Keep restart time < `session.timeout.ms`** so rolling deploys don't rebalance.
5. **Make every handler idempotent** — scale-down and rebalance *will* reprocess the last
   uncommitted batch.
6. **Size shared stores** (DB pool, ES) for `pods × per-pod concurrency`, and schedule purge
   jobs for outbox/dedup tables.
7. **Streams:** set `num.standby.replicas ≥ 1` and expect state-store restore time on
   scale-out; **Connect:** distributed mode, ≥2 workers, but know the Debezium source is a
   single active reader.
8. **Production broker floor** regardless of app replicas: RF ≥ 3, `min.insync.replicas=2`,
   `acks=all` (already the default in `SafeProducerProps`).

---

## TL;DR

Scaling these apps is not "add more pods." It is: **partitions cap parallelism, identity must
be stable, restarts must be faster than the session timeout, handlers must be idempotent, and
shared stores must absorb the concurrency.** Get those five right and every module in this repo
scales linearly up to its partition count while keeping its guarantee. Get identity or
partition count wrong and you get idle pods, rebalance storms, fencing loops, or a corrupted
read model — the exact failures the modules were built to prevent.
