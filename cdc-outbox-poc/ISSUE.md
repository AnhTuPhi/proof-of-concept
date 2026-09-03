# ISSUE — What this POC is trying to answer

> Note: the top-level directory is `cdc-outbox-poc`. Some earlier prompts refer to the
> same repo as `batch-ingest-poc` — that is a different POC. This document is specifically
> about the CDC + Transactional Outbox story.

## 1. The problem in one paragraph

We need a service to **commit business state to a database and publish a matching event
to Kafka as if the two were one atomic operation** — even though they are not, cannot be
made so cheaply (2PC/XA is a bad idea for Kafka), and any naive "write DB then send Kafka"
is a well-known way to invent silent inconsistencies. The producer must never publish a
ghost event for a rolled-back transaction, must never swallow an event for a committed one,
must let the consumer be restarted / redeployed / scaled without producing duplicates in
the real world, and must degrade to backpressure — not corruption — when Kafka, Debezium,
or the DB is unavailable. This POC is the reference answer using **Postgres logical
replication + Debezium's Outbox Event Router SMT + a Kafka consumer with an idempotency
ledger**, inside plain Spring Boot 3.4 / Java 21.

## 2. The hard sub-problems

Each is a real production failure mode we want to eliminate — not a theoretical concern.

### 2.1 The dual-write problem
`orderRepository.save(order); kafkaTemplate.send(...);` is broken in four ways: DB
succeeds and Kafka fails; Kafka succeeds and DB rolls back; the send happens *after*
some later step throws inside the same method; retries reorder messages. We need the
producer to **commit or not commit — and never disagree with what got published**.

### 2.2 Where the events actually leave the process
The producer's own JVM is the wrong place to hold "pending" messages. A crash between
"DB commit" and "Kafka ack" loses them. We need the events to **leave the producer inside
its own DB commit** and travel out via a separate, restartable pipeline that reads what
the DB already wrote.

### 2.3 At-least-once delivery on the wire, exactly-once effect at the consumer
Kafka is at-least-once. A rebalance, an in-flight commit that lost the race with a crash,
or a manual offset reset can and will replay messages. We need the consumer to make its
**side effect idempotent** on the event's stable id — not on the topic/partition/offset,
which change under replay.

### 2.4 Ordering per aggregate, not globally
Two `OrderPaid` events for the same order must be applied in the order they were emitted.
Two events for *different* orders don't care. We need **per-aggregate ordering** without
paying for a global single-partition topic.

### 2.5 Outbox table growth
The outbox table only grows. Debezium does not consume rows — it consumes the WAL, and
the row it captured is still sitting in the source table. Without a retention job the
table becomes the biggest one in the schema and the vacuum bill goes up. But the retention
window has a **subtle correctness constraint**: it must be longer than the worst-case
Debezium lag, or we delete rows before capture and lose events.

### 2.6 Poison messages and deserialisation failures
A bad JSON payload will fail deserialisation on the consumer side forever. Retrying
in-place stalls the whole partition. We need **bounded retries with exponential backoff,
then a dead-letter topic** and an operator-visible signal that a message went there.

### 2.7 WAL bloat while Debezium is down
The Postgres replication slot pins the WAL until the consumer acknowledges. A Debezium
outage that outlasts `max_wal_size` fills the disk. We need to acknowledge this and give
operators a lever (heartbeats, monitoring, and a documented drop-the-slot procedure) —
not silently corrupt the source DB.

### 2.8 Cleanup DELETEs re-entering the pipeline
The cleanup job DELETEs old outbox rows. Postgres writes those DELETEs to the WAL.
Without care, Debezium re-emits them as messages — and now the consumer sees a
"delete" for every old event. We need the connector configured so **cleanup is
invisible to the wire**.

### 2.9 Observability across three moving parts
When "the order wasn't notified" happens, the diagnosis has to answer: did the outbox row
land? Did Debezium capture it? Did Kafka store it? Did the consumer see it and dedup?
Each hop needs its own visible signal.

## 3. Constraints we accepted going in

- **Java 21 / Spring Boot 3.4.** No Vert.x, no Micronaut, no Kotlin.
- **Postgres 16 with `wal_level=logical` + `pgoutput`.** No Debezium embedded engine,
  no polling worker inside the producer.
- **Kafka 3.8 in KRaft mode.** One broker in the PoC. Any prod deployment obviously needs
  more; the code is unchanged.
- **Debezium 2.7 with the Outbox Event Router SMT.** Everything the pattern needs is in
  the SMT config — the producer's Java code never mentions Kafka.
- **Two databases.** `cdc` (source, holds `orders` + `outbox_events`) and `notifications`
  (consumer's dedup ledger). Shared cluster, separate logical DBs.
- **Ops surface = REST + Actuator + Kafka UI.** No bespoke tool.
- **No XA / 2PC.** The whole point of the design is not to need it.

## 4. Explicit non-goals

- **Schema Registry / Avro.** JSON on the wire keeps the PoC readable. Any real
  production rollout should evaluate Avro + Schema Registry for schema evolution.
- **Authentication / authorization on the order API.** The endpoint is open. Gate with
  Spring Security or a network policy before exposing.
- **Cross-region replication.** Add MirrorMaker 2 if needed; out of scope here.
- **End-to-end encryption.** Use Kafka SASL/SSL + Postgres SSL for real deployments.
- **Multiple aggregate types in one outbox.** The schema supports it (`aggregate_type`),
  but the PoC only exercises `Order`. Adding a `Customer` aggregate is a partitioner-free
  change: same table, same connector, the SMT routes to `outbox.event.Customer`.
- **Removing the outbox row on capture.** Debezium doesn't need it. The cleanup job
  removes rows on age, not on capture confirmation.
- **CDC on the business tables (`orders`) directly.** That leaks internal schema to
  consumers. The outbox is the contract; the business table is an implementation detail.

## 5. Success criteria

A PoC is "good" if all seven statements below hold on the same code:

1. **`@Transactional createOrder`** commits `orders` + `outbox_events` in one DB TX; a
   forced rollback leaves neither behind, and Kafka never sees the ghost event.
   (Test: [OrderServiceTransactionalOutboxTest](order-service/src/test/java/com/example/cdc/order/service/OrderServiceTransactionalOutboxTest.java))
2. **Kill Kafka**, then `POST /api/orders`. The order + outbox row still commit. Restart
   Kafka; the event arrives at the consumer within seconds of Debezium reconnecting.
3. **Kill Debezium** mid-run. The WAL grows but the producer keeps accepting writes.
   Restart Debezium; it resumes from its replication slot; no events lost, no duplicates.
4. **Deliver the same event twice** (manual re-publish or consumer offset reset). The
   consumer sees the second copy, finds it in `processed_events`, and logs "already
   processed" instead of firing the side effect again.
   (Test: [NotificationServiceIdempotencyTest](notification-service/src/test/java/com/example/cdc/notification/service/NotificationServiceIdempotencyTest.java))
5. **Two events for the same `Order` id** are consumed in commit order, because the SMT
   uses `aggregate_id` as the Kafka key and Kafka guarantees per-partition order.
6. **A malformed JSON message** is retried with exponential backoff (1s → 2s → 4s → ...
   capped at 30s, max 120s total), then routed to `outbox.event.Order.DLT` — not looped
   forever, not silently swallowed.
7. **The cleanup job** deletes outbox rows older than 7 days without triggering downstream
   events (connector filters DELETEs / uses `tombstones.on.delete=false`).

Everything in [TECHNICAL.md](TECHNICAL.md), [CONSISTENCY.md](CONSISTENCY.md), and
[docs/flow.html](docs/flow.html) exists to show *how* those seven statements are met.
