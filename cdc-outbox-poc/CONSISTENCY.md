# CONSISTENCY.md — What happens when we scale the pods (or VMs)

The base POC runs **one producer, one Debezium connector task, one consumer, one Kafka
broker, one Postgres**. In production you'll want more of some of those — but not all
of them at the same rate, and not in the same way. This document is the checklist for
keeping the pipeline correct when there is more than one of each.

The short version: **the producer scales cheaply, the consumer scales with topic
partitions, and Debezium is the piece that requires care — one connector task per
Postgres logical slot, no exceptions.**

---

## 1. The four consistency risks scaling introduces

### Risk A — Ghost events at the producer
Two producer pods writing to the same DB. A pod crashes between "SQL commit" and "HTTP
response". A retry-happy client resubmits. Without an idempotency key we now have two
orders and two outbox rows. The outbox pattern does not solve HTTP-level idempotency —
it solves DB↔Kafka atomicity for whatever the caller *did* submit.

### Risk B — Two Debezium tasks on one slot
Postgres logical replication is **one active reader per slot**. If two Debezium tasks
race to attach to `debezium_order_outbox`, one wins and the other errors. This is not
a bug — it's the DB refusing to hand out inconsistent state. Any Debezium HA design
must guarantee one active task at a time.

### Risk C — Consumer instances processing the same event
Kafka rebalances during a rolling deploy. Two instances briefly both hold the same
partition. Both see the same message. Without the dedup ledger being *shared*, both
fire the side effect.

### Risk D — Replication slot growing without bound
Debezium is down or lagging. The Postgres replication slot pins the WAL at the last
confirmed flush LSN. Disk fills. The source database becomes read-only when the volume
is full. This is a **producer-side outage** caused by a **CDC-side problem**.

---

## 2. The four invariants we must maintain

1. **Exactly one Debezium task reads the outbox slot at any time.** No exceptions.
   Postgres will refuse a second attach, but the mode you failed into may be
   silently unhealthy (a task in a crash loop is not the same as no task).
2. **The consumer's dedup ledger is a single logical DB** that all consumer replicas
   share. Two replicas with two ledgers is two replicas each doing "exactly once"
   *independently*, which is at-most-twice.
3. **The producer's DB commit is the release moment.** Kafka is not consulted for the
   response. This is what lets the producer scale horizontally without coordination.
4. **The WAL retention window is longer than the maximum Debezium lag and longer than
   the outbox cleanup retention.** Break either and you lose events.

If any of the four invariants breaks, one of the risks in §1 becomes real.

---

## 3. Per-component scaling — what changes, what doesn't

### Producer — cheap horizontal scale
- **N producer pods behind a load balancer** is a solved problem. All pods share one
  DB, `@Transactional` handles concurrent writes, and the outbox insert is a normal
  row insert with a UUID PK (no contention).
- **What must be centralised** — the Postgres cluster. Do not shard the outbox by
  producer identity; the Debezium slot reads the whole table.
- **What can stay per-pod** — everything else. No leader election, no cluster
  membership.
- **Autoscaling signal** — HTTP RPS or DB pool utilisation. The outbox insert cost
  is dwarfed by whatever the caller's business logic does.
- **Rolling deploy** — safe. A pod that dies mid-`@Transactional` rolls back both
  writes. A pod that dies after commit + before HTTP response makes the caller
  retry (see Risk A); the client-facing idempotency key is a separate contract.
- **Outbox cleanup on N pods** — do **not** run `OutboxCleanupJob` on all N. Use
  Spring's `ShedLock` or a Postgres advisory lock keyed on `"outbox-cleanup"` so
  exactly one pod runs the DELETE. Otherwise N pods hammer the same rows with
  `DELETE WHERE created_at < ?` at 03:00.

### Debezium — the hard part
The Postgres connector is single-task by design. You cannot scale it out to increase
per-slot throughput. You can only make it **highly available**.

Three shapes, in order of increasing complexity:

**Shape D1 — one Debezium container, restarted by the platform.**
Simplest. Kafka Connect's `standalone` mode; runs one instance; on crash, the platform
restarts. Downtime = restart time. All state (`configs / offsets / statuses`) is in
Kafka topics, so the new instance resumes cleanly.
- **Invariant 1:** held by the DB (no second task can attach anyway).
- **Downtime:** seconds to minutes per restart.
- **When it's enough:** low-value pipelines, dev/staging.

**Shape D2 — Kafka Connect distributed mode with N workers.**
Recommended for prod. Connect's own leader election assigns the connector's single
task to exactly one worker. If that worker dies, Connect reassigns to another.
- **Invariant 1:** held by Connect's task-assignment protocol. Never two active tasks.
- **The connector config is stored in `debezium_connect_configs` — the config topic
  itself must be replicated (`replication.factor >= 3`) or you lose the connector
  definition on broker loss.**
- **Downtime:** typically seconds during a failover.
- **When it's the right choice:** any real production deployment.

**Shape D3 — separate Debezium fleets per source table.**
Not really "scaling out" — it's *fanning out* across source shards. One connector per
logical slot, one slot per aggregate family. If the outbox table sits behind a
Citus / Postgres partition on `aggregate_type`, we can register N connectors, each
capturing one partition, and each writing to its own topic. This is Shape D2 replicated
N times, not a way to speed up one slot.

**What we do NOT do:**
- **Never** raise `tasks.max` above 1 on a Postgres connector. It doesn't work.
- **Never** point two connectors at the same slot name. Only one wins.

### Consumer — the "just add partitions" case
Kafka scales consumer throughput by **partition count**, not consumer count. The
consumer group can have at most `partitions` active members. Adding an 11th consumer
to a 10-partition topic just adds an idle standby.

- **N consumer pods, one topic, P partitions, one group id.** Kafka assigns partitions
  round-robin across the group. Any rebalance re-assigns cleanly.
- **The dedup ledger must be one logical DB across all N.** In this PoC, that is the
  `notifications` database. All consumer pods write to it. The PK on `event_id`
  breaks the tie under a rebalance race.
- **P (partitions) is the ceiling.** If we expect to run 12 consumers on a topic
  we need P ≥ 12. The SMT does not choose the partition count — Kafka's default
  auto-create policy does, and it's usually wrong for prod. Pre-create the topic
  with the right partition count.
- **Rolling deploy** — cooperative rebalancing (`ConsumerPartitionAssignor` set to
  `CooperativeStickyAssignor`) minimises stop-the-world during a rolling deploy.
  Not wired in the PoC; a two-line change in `KafkaConfig`.

---

## 4. What must be centralised (and what must not be)

### Must be centralised — same instance, all pods/tasks point at it
- **Postgres cluster** for the producer + Spring Batch metadata style state. Its HA
  is a separate problem (streaming replica, PITR, `pg_rewind`, etc.).
- **The Kafka cluster** with `replication.factor >= 3` for both the outbox topics
  and the Debezium internal topics (`configs / offsets / statuses`).
- **The consumer's dedup ledger** — the `notifications` database. All consumer
  replicas share it; two ledgers is two independent "exactly onces".
- **Debezium Connect cluster state** — the three internal topics. Replicated.

### Must NOT be centralised — must live per-pod
- **The producer's HTTP handler thread pool, connection pool, Jackson mapper.** Per-JVM.
- **The consumer's Kafka client** — every replica has its own consumer instance and
  its own poll loop. Sharing would be an anti-pattern.
- **In-memory caches.** Every service's caches are per-pod, warmed on start.

---

## 5. WAL and replication-slot management (the D-risk deep dive)

The single most operationally sensitive part of the whole design.

- **A replication slot pins the WAL from the confirmed flush LSN forward.** If
  Debezium never confirms, the WAL never gets recycled. Postgres will let the disk
  fill and take the database down.
- **Heartbeats are the escape hatch.** `heartbeat.interval.ms=10000` in the connector
  config makes Debezium emit a heartbeat every 10s that advances the slot even when
  no outbox rows have been written. Without this, a quiet outbox + a busy WAL (from
  other tables in the same DB) can freeze the slot at a very old LSN.
- **Monitor `pg_replication_slots`.** Alert on `confirmed_flush_lsn` falling behind
  `pg_current_wal_lsn()` beyond your SLO. This is the single most important CDC
  metric to have on a dashboard.
- **Have a documented "drop the slot" procedure.** In the worst case (Debezium is
  broken for days, the disk is filling, replay is not acceptable), an operator drops
  the slot and re-creates the connector with `snapshot.mode=never`, accepting that
  events queued since the last confirmed LSN are lost. This must be a **known,
  approved runbook**, not an emergency improvisation.
- **`wal_keep_size` (Postgres) and `max_wal_size`** are the ceiling. Set them high
  enough that a night of Debezium being down does not take the DB down.

---

## 6. Kubernetes-specific checklist

- **Producer deployment** — plain HPA on CPU or HTTP RPS. `replicas: 2..N`.
  No special care needed.
- **Debezium Connect deployment** — StatefulSet or Deployment; **`replicas: 2..3`**
  running in distributed mode; only one worker will actually hold the connector task
  at any time. Autoscaling Connect workers is not the same as scaling Debezium
  throughput; the connector is single-task regardless.
- **Consumer deployment** — plain HPA on Kafka consumer lag (via KEDA or the
  Prometheus adapter). Cap `replicas` at the partition count of the source topic.
  Autoscaling to `replicas > partitions` gains nothing.
- **`terminationGracePeriodSeconds`** on the consumer must be ≥ the max poll interval
  (default 5 min) so a rolling deploy doesn't kill a consumer mid-batch. On the
  producer, ≥ the longest in-flight `@Transactional` operation.
- **`preStop` hook on the consumer** — a `curl -X POST /actuator/shutdown` after
  gracefully unassigning is overkill for the PoC; letting Kafka's cooperative
  rebalance handle it is fine.
- **PodDisruptionBudget** — `maxUnavailable: 0` on Debezium's Deployment during a
  drain; the connector's failover is fast but not zero.
- **Persistent storage** — Postgres is a StatefulSet or externally managed.
  Kafka broker data is a StatefulSet with real PVs. Connect state lives in the
  broker topics, so Connect itself needs no PV.
- **Config alignment** — three places to keep in sync (services' `application.yml`,
  the compose/Helm env block, the Debezium connector JSON). A CI check that diffs
  the three against a source of truth is worth writing.

---

## 7. VM-fleet checklist (no K8s)

- **Same shared Postgres for producer state.** Non-negotiable.
- **Same shared Postgres (different DB) for the consumer's dedup ledger.**
- **Same shared Kafka cluster** — obviously.
- **One VM runs Debezium Connect at a time.** Elect via keepalived / consul lock /
  a systemd unit gated on a shared lease. Others sit ready.
- **Deploy the same JARs to every VM.** The Debezium role is a config toggle
  (which container is running), not a different artifact.
- **The outbox cleanup cron must be centralised** — pick one VM, or use a
  Postgres advisory lock, so the DELETE runs once per night.

---

## 8. Failure modes to walk through before shipping

Run each scenario against your chosen shape *before* production:

| Scenario | Expected behaviour |
|----------|-------------------|
| One producer pod dies mid-`@Transactional` | Postgres rolls back both writes; client retry or resubmit creates a new order (or dedups via `Idempotency-Key`, if you added one). No orphan outbox row. |
| Debezium worker dies mid-capture | Connect reassigns the task to another worker; the new task resumes from the last confirmed LSN; no events lost, no duplicates. |
| Kafka broker dies | Producer keeps accepting HTTP; outbox rows keep landing in Postgres; Debezium retries and catches up when a broker is available. Rows pile up in the outbox and in the WAL — retention needs to accommodate. |
| Two consumer pods hold the same partition briefly during a rebalance | Both call `existsById`; one wins the PK insert; the other's TX rolls back with `DataIntegrityViolationException`; the error handler retries; the retry sees the row and no-ops. Side effect fires once. |
| Poison message | Retried with exponential backoff up to 120s total, then routed to `outbox.event.Order.DLT`; `DltConsumer` logs it; offset commits so the partition unblocks. |
| Outbox cleanup runs while Debezium is 8 days behind (retention=7 days) | **Data loss.** Rows are deleted before the WAL is captured. Alert on slot lag *before* it approaches the retention window. |
| WAL disk pressure at 80% | Alert fires; on-call inspects `pg_replication_slots`; either restart Debezium (if it's the cause) or execute the documented drop-slot runbook. |
| Two connector definitions accidentally registered on the same slot | The DB refuses the second; Connect surfaces it as a failed task. Detect via Connect's `/connectors/{name}/status` in a probe. |
| Rolling deploy of the consumer | Cooperative rebalance re-assigns partitions; in-flight polls finish; new pods pick up. No message loss, occasional duplicates handled by dedup. |

If any of these behaves differently, the pipeline is not yet consistent — fix that
before raising the replica count on the affected tier.

---

## 9. Summary — the one-liner

**The producer scales like a stateless web service; the consumer scales by Kafka
partitions and a shared dedup ledger; Debezium does not scale — it fails over.
The Postgres WAL and its replication slot are the single most operationally
sensitive point of the whole pipeline, and every SLO ultimately traces back to
"is the slot advancing fast enough?"**
