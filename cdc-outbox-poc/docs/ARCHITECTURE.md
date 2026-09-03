# Architecture

## The dual-write problem

A naive event-driven service often looks like this:

```java
// DON'T DO THIS
orderRepository.save(order);
kafkaTemplate.send("orders", new OrderCreated(order));
```

This is broken in four interesting ways:

1. **DB succeeds, Kafka fails.** Process crashes or broker is down — the consumer never hears about the order.
2. **Kafka succeeds, DB rolls back.** Some later code in the same method throws, the transaction rolls back, but the message is already in the broker. Consumers act on a "ghost" order.
3. **Both succeed but in wrong order.** Some implementations flip the order — send to Kafka first, then write to DB. Same family of bugs, reversed.
4. **Reordering under retry.** Async retries reorder messages across partitions.

Two-phase commit (XA) "solves" this, but in practice it's slow, has its own coordinator failure modes, and most message brokers (Kafka included) don't support it well.

## Transactional Outbox

The pattern flips the problem:

```java
@Transactional
void createOrder(...) {
    orderRepository.save(order);            // write business state
    outboxRepository.save(outboxEvent);     // write event payload
}                                           // ONE atomic commit
```

Both rows land in the same DB transaction. Either both are durable, or neither is. The DB is the source of truth.

A separate process — Debezium — tails the Postgres WAL, reads new outbox rows, and publishes them to Kafka. The producer service never speaks to Kafka directly.

## Why use CDC instead of polling the outbox?

A simpler-looking alternative is a polling worker:

```java
@Scheduled(fixedDelay = 100)
void publishOutboxBatch() {
    List<OutboxEvent> batch = outboxRepository.findUnpublished(100);
    for (var event : batch) {
        kafka.send(event);
        outboxRepository.markPublished(event);
    }
}
```

This works, but has costs:

- **DB load.** Every 100 ms you scan the outbox even when there's nothing to do.
- **Latency floor.** Average latency = pollInterval / 2.
- **Lock contention.** Multiple workers need `SELECT ... FOR UPDATE SKIP LOCKED`.
- **Dual-write again.** The `markPublished` UPDATE happens *after* the Kafka send — so you're back to a "write-then-mark" problem (Kafka succeeds, mark fails, message gets re-sent on next poll).

CDC sidesteps all of this. Debezium reads the Postgres WAL (which the DB writes anyway), so there's near-zero added load on the source. Latency is in tens of milliseconds. The replication slot tracks progress atomically.

## The Outbox Event Router SMT

Debezium ships an SMT (Single Message Transformation) called `EventRouter` specifically for this pattern. Given an outbox row like:

| id (UUID) | aggregate_type | aggregate_id | event_type    | payload (JSONB)         | created_at |
|-----------|----------------|--------------|---------------|-------------------------|------------|
| e1...     | Order          | o7...        | OrderCreated  | `{"id":"o7...",...}`    | ...        |

The SMT produces:

- **topic**: `outbox.event.Order` (built from `route.topic.replacement` + `aggregate_type`)
- **key**: `o7...` (the `aggregate_id` — preserves per-aggregate ordering within a partition)
- **value**: the raw JSON payload
- **headers**: `id=e1...`, `type=OrderCreated`

No tombstones, no Debezium envelope — clean business events on the wire.

## Per-aggregate ordering

Kafka guarantees ordering within a partition. Using `aggregate_id` as the message key ensures all events for the same Order land in the same partition, so consumers see them in commit order. Cross-aggregate ordering is not guaranteed (and rarely needs to be).

## Consumer idempotency

Kafka is at-least-once. A consumer rebalance, a crash before offset commit, or a slow replica can cause the same message to be delivered twice.

The notification-service handles this with a `processed_events` table:

```sql
CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);
```

The Debezium SMT puts the outbox row UUID into the message header `id`. The consumer reads it and tries to `INSERT INTO processed_events`. A unique-violation means "already handled — skip the side effect."

This works because the INSERT and the side effect are in the same transaction. Either both happen or neither does.

## What's in the WAL, exactly?

When you `INSERT INTO outbox_events ...`, PostgreSQL writes to the WAL:

```
WAL: BEGIN
WAL: INSERT outbox_events ROW [...all column values...]
WAL: COMMIT
```

Because we set `REPLICA IDENTITY FULL`, the WAL captures every column (vs. just the PK by default). Debezium's logical decoding plugin (`pgoutput`, built into Postgres 10+) turns the WAL into a stream of change events.

We don't need the BEFORE image for INSERTs, but having `REPLICA IDENTITY FULL` makes operations like DELETEs visible too — useful if a cleanup job's DELETE accidentally triggers downstream processing. It's a small cost (more WAL bytes) for correctness flexibility.

## Outbox table retention

The outbox table grows monotonically. Debezium does not consume rows — it reads the WAL. So we need to clean up.

`OutboxCleanupJob` runs nightly and deletes rows older than 7 days. The retention window must be **longer than the worst-case Debezium lag**. If Debezium is down for 8 days and the cleanup window is 7, you'll lose events.

Tune the retention to your SLO. If your Debezium fleet has a 1-hour worst-case lag and you alert at 30 minutes, a 2-hour retention is probably enough — but disk is cheap, so we default to 7 days.

## Failure modes covered

| Scenario                                      | Behavior                                                        |
|-----------------------------------------------|-----------------------------------------------------------------|
| `order-service` crash after `orders` insert   | Same TX — outbox row is rolled back too. No ghost event         |
| `order-service` crash mid-HTTP response       | TX already committed; outbox row is durable; Debezium catches up |
| Kafka unavailable                             | Debezium retries; rows accumulate in outbox until broker returns |
| Debezium unavailable                          | WAL retains until slot advances. Tune `max_wal_size` accordingly |
| `notification-service` crash mid-batch        | Reads from last committed offset; idempotent via processed_events |
| Poison message (deserialize fail)             | Retries with backoff, then DLT → `outbox.event.Order.DLT`       |
| Replay (manual offset reset)                  | Consumer dedups via processed_events                            |

## What this PoC does NOT include

For brevity and focus:

- **Schema Registry / Avro.** JSON payloads keep things readable. Production should consider Avro + Schema Registry for schema evolution.
- **Multiple connector instances.** Debezium PostgreSQL connector is single-task by design (one replication slot per connector). For HA, run Connect in distributed mode and lean on its built-in failover.
- **Cross-region.** Add MirrorMaker 2 if needed.
- **End-to-end encryption.** Use Kafka SASL/SSL + Postgres SSL in production.
- **Authentication on the order-service API.** Add Spring Security in front for real usage.

## References

- Debezium docs on the Outbox Event Router: <https://debezium.io/documentation/reference/transformations/outbox-event-router.html>
- The pattern in Microservices Patterns (Chris Richardson), Chapter 3
- "Reliable transactional outbox in Kafka" — Confluent blog
