# TECHNICAL.md — Problem, solution shape, and tech debt per module

This document is the engineering companion to [ISSUE.md](ISSUE.md). For each module
it states: **the hard problem**, **what we are protecting**, **the solution shape**,
**key tech by responsibility**, **how it solves each sub-problem**, and **the tech
debt we knowingly accept**.

A shared reading for all modules first, because the same primitives recur:

### Cross-cutting foundations

- **Delivery semantics are a property of the *whole loop*, not a single setting.**
  "Exactly-once" means *exactly-once effect*, achieved by combining an idempotent/
  transactional producer, `read_committed` consumers, and either transactional offset
  commits or an idempotent side effect. No single flag delivers it.
- **The offset is a promise, not a receipt.** Committing an offset says "everything
  up to here is *done*." If you commit before the work, a crash loses the work. This
  is the root cause behind half the modules.
- **Shared safe defaults** live in `common/`:
  - `SafeProducerProps` → `acks=all`, `enable.idempotence=true`,
    `max.in.flight=5`, `retries=MAX` with a bounded `delivery.timeout.ms`.
  - `SafeConsumerProps` → `enable.auto.commit=false`, `isolation.level=read_committed`,
    `CooperativeStickyAssignor`, tuned `max.poll.interval.ms`, optional static membership.
  - `DomainEvent` / `EventHeaders` → a stable event envelope carrying `eventId`
    (the idempotency key), `eventType`, `occurredAt` (the ordering key), and source app.

---

## 01 — Idempotent Producer

**Hard problem.** With `acks=1`, the broker acknowledges as soon as the *leader*
writes. If the leader dies before followers replicate, the record is gone — silently.
With retries and `max.in.flight > 1` on a non-idempotent producer, a retried batch
can also land *out of order* or *twice*.

**What we protect.** Every produced business fact: no silent loss on leader failover,
no duplicates within a producer session, no reordering under retry.

**Solution shape.** A "safe by default" producer factory versus an "unsafe" one, with
an HTTP endpoint to fire N records through each and count what actually arrives.

**Key tech by responsibility.**
| Responsibility | Tech |
|---|---|
| Durability on failover | `acks=all` + `min.insync.replicas` on the broker |
| No dup / no reorder under retry | `enable.idempotence=true` (producer epoch + sequence numbers) |
| Ordering ceiling | `max.in.flight.requests.per.connection=5` |
| Fail-fast SLO | bounded `delivery.timeout.ms` with `retries=MAX` |
| Hot-partition mitigation | sticky partitioner / `partitioner.ignore.keys=false` |

**How it solves each sub-problem.** `acks=all` waits for the in-sync replica set, so a
leader loss can't drop an acknowledged record. Idempotence tags each record with a
(producer id, epoch, sequence) so the broker deduplicates retries and rejects
out-of-order sequences — you get ordering *and* de-dup even with 5 in-flight requests.

**Tech debt.** Idempotence guarantees are **per producer session** only — a process
restart gets a new producer id, so cross-restart dedup needs the consumer side
(module 03) or transactions (module 02). Compression is `zstd` (Kafka 4.x); older
brokers need `lz4`.

---

## 02 — Transactions (read-process-write)

**Hard problem.** A stream processor reads an event, produces to *two* output topics,
and must mark the input consumed. If it produces to topic A, then crashes before
topic B and the offset commit, a restart reprocesses and double-produces to A.

**What we protect.** Atomic fan-out: either both outputs *and* the input offset commit,
or none do. Downstream never sees a half-written batch.

**Solution shape.** The canonical loop in `ReadProcessWriteService`: `beginTransaction`
→ produce to `orders.paid.v1` + `shipping.requested.v1` → `sendOffsetsToTransaction`
→ `commitTransaction`, all inside one transaction, consumed downstream at
`read_committed`.

**Key tech by responsibility.**
| Responsibility | Tech |
|---|---|
| Atomic multi-topic write + offset | `sendOffsetsToTransaction` + `commitTransaction` |
| Never expose aborted writes | consumer `isolation.level=read_committed` |
| Zombie fencing after crash | stable `transactional.id` (per pod ordinal / hostname) |
| Correct failure response | catch `ProducerFencedException` → **shut down**, don't recover |

**How it solves each sub-problem.** The offset commit is *part of the transaction*, not
a separate `commitSync`. So "consumed" and "produced" share one atomic outcome. A
restarted zombie with the same `transactional.id` is fenced by epoch bump; the only
correct reaction is to stop.

**Tech debt.** Throughput cost: transactions add a commit round-trip and buffering.
`transactional.id` must be **stable and unique per instance** — deriving from
`HOSTNAME` works in K8s StatefulSets but is fragile for Deployments with random pod
names (see [CONSISTENCY.md](CONSISTENCY.md)). The demo uses raw clients; a real system
would likely use Spring Kafka's `KafkaTransactionManager`.

---

## 03 — Offset Management (the most important module)

**Hard problem.** *When* do you commit the offset relative to doing the work? Get it
wrong and you either lose messages (commit-before-work) or the "quickstart" auto-commit
silently loses them for you.

**What we protect.** The guarantee that a message is only marked done *after* its side
effect succeeded — and that replays don't double-apply.

**Solution shape.** One `DemoConsumer` parameterized by `CommitMode`, with an injectable
crash-after-N, comparing four strategies side by side.

| Mode | On crash mid-processing | Guarantee |
|---|---|---|
| `AUTO` | offset already committed, work lost | at-most-once (lossy) |
| `SYNC_BEFORE` | same loss | at-most-once (anti-pattern) |
| `SYNC_AFTER` | replays on restart | at-least-once |
| `IDEMPOTENT_AFTER` | replays, deduped by DB unique key | exactly-once *effect* |

**Key tech by responsibility.**
| Responsibility | Tech |
|---|---|
| Control commit timing | `enable.auto.commit=false` + explicit `commitSync` |
| At-least-once | commit strictly *after* the side effect |
| Dedup on replay | `IdempotencyStore` — unique constraint on `message_id` |
| Stable idempotency key | `EventHeaders.EVENT_ID` (falls back to topic-partition-offset) |

**How it solves each sub-problem.** `SYNC_AFTER` turns crashes into replays instead of
loss. `IDEMPOTENT_AFTER` adds an insert-or-reject dedup table so replays are absorbed —
the closest you get to exactly-once side effects without XA.

**Tech debt.** For true correctness the dedup insert and the side effect must be in the
**same DB transaction** (documented in `IdempotencyStore`); the demo counts instead of
doing real work, so it doesn't wrap them together. Per-record `commitSync` is clear but
slow — production batches the commit at end-of-poll. The dedup table grows unbounded
without a TTL/partition-drop job.

---

## 04 — DLQ + Poison Message

**Hard problem.** A single message that always fails (bad schema, missing field) blocks
its partition forever if you retry in-line. A transient failure (downstream timeout)
shouldn't be treated the same as a permanent one.

**What we protect.** Partition liveness — one bad record must not stall the healthy ones —
and a recoverable place for quarantined messages.

**Solution shape.** Spring Kafka `@RetryableTopic` with **non-blocking** retries, an
exception taxonomy (`TransientException` retries, `PoisonMessageException` short-circuits
to DLQ), a `@DltHandler` for forensics/alerting, and an operator **replay** endpoint.

**Key tech by responsibility.**
| Responsibility | Tech |
|---|---|
| Non-blocking retry | `@RetryableTopic` republishes to `.retry` topics with backoff |
| Backoff policy | `@Backoff(delay, multiplier, maxDelay)` exponential |
| Retriable vs poison | `include=TransientException`, `exclude=PoisonMessageException` |
| Quarantine + forensics | `@DltHandler` → persist, alert on **rate**, never re-throw |
| Recovery without redeploy | `DlqReplayController` replays from `.dlq` on demand |

**How it solves each sub-problem.** Because retries live on separate topics, the main
partition never blocks on a slow/failed record. Poison messages skip retries entirely.
On-call replays the DLQ at 3 AM instead of shipping a hotfix.

**Tech debt.** Retry topics multiply topic count and can reorder relative to the source
(acceptable for independent records, not for a per-key ordered stream). `autoCreateTopics=true`
is convenient but violates the "no auto-create" rule the rest of the repo enforces —
in production, pre-create retry/DLQ topics with correct partitions and retention.

---

## 05 — Rebalancing + Backpressure

**Hard problem.** Two failure modes at once: (a) every rolling deploy triggers an *eager*
"stop-the-world" rebalance that pauses all consumers for seconds; (b) a slow handler blows
past `max.poll.interval.ms`, the broker evicts the member, its partitions migrate to
another (also slow) consumer → rebalance storm.

**What we protect.** Availability and progress during deploys and load spikes.

**Solution shape.** One consumer combining **static membership**, the **cooperative-sticky**
assignor, and **pause/resume** backpressure — while *still polling* during the pause so
the group heartbeat keeps flowing.

**Key tech by responsibility.**
| Responsibility | Tech |
|---|---|
| No rebalance on short restart | `group.instance.id` static membership |
| Incremental (non-STW) rebalance | `CooperativeStickyAssignor` |
| Measure real pause | `ConsumerRebalanceListener` timing revoke→assign |
| Backpressure | `pause()` above high-water, `resume()` below low-water |
| Stay in group while paused | keep calling `poll()`; bound `max.poll.records` |
| No dup on revoke | `commitSync()` in `onPartitionsRevoked` |

**How it solves each sub-problem.** Static membership makes a 10-second pod restart
invisible to the group. Cooperative-sticky revokes only the partitions that actually move.
Pause/resume throttles intake without leaving the group, so `max.poll.interval.ms` never
fires.

**Tech debt.** Static membership requires **stable, unique** `group.instance.id`s — again a
StatefulSet concern (see [CONSISTENCY.md](CONSISTENCY.md)); duplicate ids fence each other.
A too-long `session.timeout.ms` delays detection of a genuinely dead pod. The in-flight
counter here is a single-process gauge, not a distributed backpressure signal.

---

## 06 — Transactional Outbox

**Hard problem.** The **dual-write problem**: you must update Oracle *and* publish a Kafka
event. Do them as two independent operations and one can succeed while the other fails —
either a ghost event for an order that rolled back, or a lost event for an order that
committed.

**What we protect.** Event completeness: the DB row and its event are all-or-nothing.

**Solution shape.** `OrderService` inserts the `orders` row and an `outbox` row **in the
same Oracle transaction**. A separate `OutboxPoller` ships unpublished rows to Kafka and
marks them published only after broker ack.

**Key tech by responsibility.**
| Responsibility | Tech |
|---|---|
| Atomic state + intent | single `@Transactional` insert of order **and** outbox row |
| Parallel-safe draining | `SELECT ... FOR UPDATE SKIP LOCKED` |
| Bounded recovery | fixed `BATCH_SIZE`, publish then `UPDATE published_at` |
| At-least-once publish | mark published only after ack; retry on next tick |
| Consumer-side dedup | outbox row id **is** the event id, carried as a header |

**How it solves each sub-problem.** There is no interleaving where the order exists but the
event doesn't — they commit together. The poller's `SKIP LOCKED` lets many instances drain
concurrently without double-publishing. Idempotent producer + event-id header means the rare
double-publish is deduped downstream.

**Tech debt.** Polling adds latency (the `fixedDelay=500ms` tick) and DB load — module 12
replaces the poller with Debezium CDC for zero-latency, zero-polling capture. The published
outbox rows are never purged here; production needs an archival/delete job. Ordering across
aggregates is only preserved by `partition_key = orderId`.

---

## 07 — Saga Orchestration (choreography)

**Hard problem.** A business transaction spans Order, Inventory, Payment, Shipping —
separate services, separate DBs, **no global transaction**. If payment fails after inventory
was reserved, the reserve must be released and the order cancelled.

**What we protect.** Eventual consistency of a multi-service workflow, with compensation
instead of rollback.

**Solution shape.** Event choreography — no central orchestrator. Each service consumes the
previous event, updates its own row in a local `@Transactional` boundary, and emits the next
event. Happy path `OrderPlaced → InventoryReserved → PaymentCompleted → ShippingScheduled`;
failure path `PaymentFailed` fans out to two compensations (release inventory, cancel order).

**Key tech by responsibility.**
| Responsibility | Tech |
|---|---|
| Step coupling | `@KafkaListener` per service consuming the prior event |
| Local atomicity | Spring `@Transactional` DB write + emit next event |
| Compensation trigger | `PaymentFailed` event → parallel compensating handlers |
| Ordering per order | event key = `orderId` keeps a saga's steps on one partition |

**How it solves each sub-problem.** No distributed lock: each hop is a local transaction plus
an event. Compensations are ordinary forward events (`InventoryReleased`, `OrderCancelled`),
so the same delivery guarantees apply.

**Tech debt.** Choreography has **no single place** that knows the saga's overall state —
debugging means tracing events across topics (an orchestrator would centralize this at the
cost of coupling). The local-write-then-emit step is itself a dual-write and should really
use the outbox (module 06) to be bulletproof; the demo emits directly for readability.
There is no saga timeout / stuck-saga detector.

---

## 08 — CQRS Projection into Elasticsearch

**Hard problem.** Building a query model from events that arrive **out of order across topics**.
An `OrderShipped` can land before its `OrderPlaced`. Overwriting the whole document on each
event loses data and lets a stale event clobber a newer state.

**What we protect.** Convergence: the read model must reach the correct final state regardless
of event arrival order, and never regress.

**Solution shape.** `OrderProjector` consumes all order events, applies **partial updates**
with `doc_as_upsert=true`, and guards every apply with an `occurredAt` comparison so older
events are discarded.

**Key tech by responsibility.**
| Responsibility | Tech |
|---|---|
| Order-independent first write | ES `_update` with `docAsUpsert(true)` |
| Discard stale updates | compare event `occurredAt` vs stored `updatedAt` |
| Partial mutation | patch only the changed fields, not the whole doc |
| Denormalized-for-query shape | `OrderReadModel` with status history |

**How it solves each sub-problem.** Upsert means a `Shipped` event that arrives first creates
the doc; a later `Placed` fills the rest but can't overwrite the newer status because the
`occurredAt` guard rejects it. The model converges to the same state under any permutation.

**Tech debt.** The `patchStatus` path does a **read-then-write** (get current doc, append
history, update) — a race window under concurrent events for the same order; a hot index
should use a scripted update (`ctx._source.history.add`) to make it atomic. The `occurredAt`
guard assumes trustworthy, monotonic-enough event timestamps. Elasticsearch external
versioning would harden this further.

---

## 09 — Kafka Streams: Windowing

**Hard problem.** Time aggregations silently drop late records. A record arriving after its
window closes vanishes with **no error** unless you configured a grace period and instrument
the drop.

**What we protect.** Completeness and correctness of time-bucketed metrics (billing, rate
limits, engagement).

**Solution shape.** One topology showing **tumbling** (per-minute counts), **hopping**
(rolling 5-min/1-min), and **session** (30s inactivity gap) windows, each with an explicit
grace period.

**Key tech by responsibility.**
| Responsibility | Tech |
|---|---|
| Discrete buckets | `TimeWindows.ofSizeAndGrace` (tumbling) |
| Rolling windows | `.advanceBy(...)` (hopping) |
| Activity sessions | `SessionWindows.ofInactivityGap...` |
| Late-data tolerance | explicit grace `Duration` per window |
| State | `Materialized.as(...)` RocksDB stores |

**How it solves each sub-problem.** Grace periods keep windows open long enough to admit
late records; the window type matches the analytical question (billing → tumbling,
rate-limit → hopping, funnel → session).

**Tech debt.** Hopping windows multiply state-store size by `size/advance` — a storage/cost
trap called out in the topology comments. Grace is a **latency-vs-completeness** trade with
no universally right value. Dropped-late-record counts are not exported as a metric here, so
"silent drops" are still only visible if you go looking.

---

## 10 — Kafka Streams: Joins

**Hard problem.** Joins produce **silently wrong** results when inputs aren't co-partitioned —
same logical key, different partition, and records simply never match with no error.

**What we protect.** Correctness of enrichment/attribution — no dropped or mismatched joins.

**Solution shape.** Three joins: **KStream-KTable** (per-user profile enrichment, co-partitioned),
**KStream-GlobalKTable** (low-cardinality lookup, replicated everywhere), and **KStream-KStream**
temporal join (click→purchase within 10 min).

**Key tech by responsibility.**
| Responsibility | Tech |
|---|---|
| Stateful enrichment | `KStream.leftJoin(KTable)` on a co-partitioned key |
| Reference lookups | `GlobalKTable` — replicated to every task, no co-partitioning |
| Temporal correlation | `KStream.join` with `JoinWindows.ofTimeDifference...` |
| Fix mismatched partitioning | `repartition()` before the join |

**How it solves each sub-problem.** Co-partitioned KStream-KTable joins are cheap and local.
GlobalKTable removes the co-partitioning requirement for small tables. The temporal join
window defines what "followed by" means for funnels/attribution.

**Tech debt.** The enrichment does **string surgery** (`click.replace("}", ...)`) instead of
real (de)serialization — fine for a demo, unacceptable in production; use proper Serdes/records.
GlobalKTable trades memory (full replication per instance) for join simplicity. The temporal
join uses no grace, so late events on either side are dropped.

---

## 11 — Schema Registry + Avro

**Hard problem.** A "harmless" schema change (rename a field, drop one, tighten a type) breaks
every consumer that hasn't been redeployed. The failure surfaces at *deserialize* time in
another team's service.

**What we protect.** The event contract — producers can evolve schemas without breaking existing
consumers.

**Solution shape.** An Avro `OrderEvent` schema whose v2 adds `shippingAddress` as a nullable
union with `default: null` — the canonical **backward-compatible** evolution — enforced by the
registry's `BACKWARD` compatibility mode.

**Key tech by responsibility.**
| Responsibility | Tech |
|---|---|
| Contract storage + compat gate | Confluent Schema Registry, `BACKWARD` mode |
| Safe additive change | new field as `["null", T]` with `default: null` |
| Typed deserialization | `specific.avro.reader=true` → generated class |
| Compact wire format | Avro binary + schema id header |

**How it solves each sub-problem.** The registry rejects an incompatible schema *at registration*,
before it can poison the topic. v1 consumers skip the new field; v2 consumers read v1 records and
see `null`. Both directions work.

**Tech debt.** The demo allows auto-registration for convenience; production must set
`auto.register.schemas=false` and register via a **CI gate**. `BACKWARD` alone doesn't cover every
evolution direction — `FULL` (or `FULL_TRANSITIVE`) is stricter when both old producers and old
consumers coexist. Renames still require an alias strategy.

---

## 12 — CDC Pipeline (Debezium Oracle → Kafka → Elasticsearch)

**Hard problem.** Get committed DB changes into Kafka and onward to a search store **without
writing or operating consumer code** in the data path — and without the polling latency of
module 06.

**What we protect.** A hands-off, low-latency, exactly-once-ish pipeline whose failure modes are
*operational* (connector health) rather than application bugs.

**Solution shape.** Two Kafka Connect connectors: a **Debezium Oracle source** (LogMiner) with the
**Outbox Event Router SMT** that splits the outbox table into per-aggregate topics, and an
**Elasticsearch sink** doing idempotent upsert by document id with its own DLQ topic.

**Key tech by responsibility.**
| Responsibility | Tech |
|---|---|
| Capture committed changes | Debezium + Oracle LogMiner (archivelog + supplemental logging) |
| Outbox → per-aggregate topics | Outbox Event Router SMT |
| Idempotent load | ES sink `key.ignore=false`, upsert by doc id |
| Failure isolation | sink DLQ topic for unmappable records |
| Data-freshness SLO | Debezium JMX `MilliSecondsBehindSource` |

**How it solves each sub-problem.** Reading the redo log means capture happens with zero app code
and near-zero latency. The SMT reshapes the single outbox stream into topics consumers expect.
Idempotent upserts survive replays; the sink DLQ keeps one bad doc from halting the pipeline.

**Tech debt.** Oracle LogMiner CDC is **operationally heavy** — archivelog growth, supplemental
logging, `MilliSecondsBehindSource` monitoring, and single-worker Connect losing state on crash
(run ≥2 workers, distributed mode). DDL surprises can corrupt schema history unless
`skip.unparseable.ddl=false`. Connect adds a whole subsystem to operate versus in-app publishing.

---

## Summary: the debt we accept everywhere

- **Replication factor 1** across the local stack (`docker-compose.yml`) — fine for a laptop,
  never for production, where RF≥3 and `min.insync.replicas=2` are the floor.
- **Demos count instead of doing real side effects** in several modules, so a few
  "must be in the same transaction" guarantees are documented rather than enforced.
- **Auto-create is used in the DLQ module** for convenience, contradicting the repo-wide
  "pre-create topics" stance.
- **No purge/TTL jobs** for the outbox, dedup, and processed-message tables.
- **Identity/stability of `transactional.id` and `group.instance.id`** is assumed and only
  fully addressed conceptually — the operational answer is in [CONSISTENCY.md](CONSISTENCY.md).
