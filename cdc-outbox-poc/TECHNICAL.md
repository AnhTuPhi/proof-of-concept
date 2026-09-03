# TECHNICAL.md — How the POC solves the hard parts

This document is the pairing document to [ISSUE.md](ISSUE.md). For each of the three
components in the pipeline — the **producer**, the **CDC layer**, and the **consumer** —
we describe **what makes it hard**, **what invariant we are protecting**, the **shape
of the solution**, the **key tech by responsibility**, **how each sub-problem is
answered**, and the **tech debt we deliberately left on the table**.

The three components share nothing at runtime except the Postgres WAL and the Kafka
topic they read/write. That is the point — each side can be restarted, redeployed, or
scaled without touching the others.

```
        ┌──────────────── shared source of truth ────────────────┐
POST /api/orders  ─►  order-service (@Transactional)             │
                       ├─ orders row               ─►  Postgres  │
                       └─ outbox_events row        ─►  WAL       │
                                                       │        │
                                                       ▼        │
                                               Debezium Connect │
                                               ├─ pgoutput read │
                                               └─ Outbox SMT    │
                                                       │        │
                                                       ▼        │
                                                    Kafka        │
                                              outbox.event.Order │
                                                       │        │
                                                       ▼        │
                       notification-service (@KafkaListener)     │
                       ├─ dedup on event_id (processed_events)   │
                       ├─ side-effect dispatch                   │
                       └─ retry + DLT on failure                 │
        └───────────────────────────────────────────────────────┘
```

---

## Component #1 — `order-service` (the producer)

**Source:** HTTP `POST /api/orders`, `POST /api/orders/{id}/pay`, `POST /api/orders/{id}/cancel`.
**Target:** two rows in Postgres, in the same transaction — the business row (`orders`)
and the outbox row (`outbox_events`). The producer never contacts Kafka.

### What makes it hard
- Two writes have to be **one commit or no commits at all** — otherwise we invented
  the dual-write problem the outbox exists to avoid.
- The outbox row has to look like whatever the downstream consumer expects on Kafka —
  key, headers, payload — even though we're just inserting a DB row.
- The outbox row must be **on the WAL as an INSERT**, not a business-table CDC event.
- The outbox table only grows. Left unchecked it becomes the biggest table in the
  schema; cleanup must not itself produce downstream events.
- The producer must not care whether Kafka or Debezium is up. Its SLO is the DB commit.

### What we are protecting
- **Atomicity of business write + event emission**: no order without its event, no event
  without its order.
- **The consumer's contract**: `aggregate_id` is the partition key, `event_type` is a
  header, `id` (row UUID) is the dedup key.
- **The source of truth**: the DB. Kafka never leads.

### Solution shape
- `OrderService.createOrder / markPaid / cancel` is `@Transactional`. Inside it we call
  `orderRepository.save(order)` and then `outboxPublisher.publish(...)`. One TX, one commit.
- `OutboxEventPublisher` is a plain `@Component` that serialises the payload with Jackson
  and inserts an `OutboxEvent` via a JPA repository. **It does not call Kafka.** It does
  not schedule anything. It has no side effect beyond the DB write.
- The `outbox_events` table has `REPLICA IDENTITY FULL` so Debezium sees every column
  on both INSERT and DELETE — the SMT needs those for the payload; the FULL identity also
  means DELETEs are visible if we ever need them.
- `OutboxCleanupJob` runs on a cron (`0 0 3 * * *` by default) and deletes rows older
  than the retention window (7 days by default). The DELETEs go to the WAL but the
  connector's SMT + filter policy makes them invisible to Kafka.

### Key tech by responsibility
| Responsibility | Component | File |
|----------------|-----------|------|
| Serve HTTP | `OrderController` | [OrderController.java](order-service/src/main/java/com/example/cdc/order/web/OrderController.java) |
| Atomic business + event write | `@Transactional OrderService` | [OrderService.java](order-service/src/main/java/com/example/cdc/order/service/OrderService.java) |
| Insert outbox row (no Kafka) | `OutboxEventPublisher` | [OutboxEventPublisher.java](order-service/src/main/java/com/example/cdc/order/service/OutboxEventPublisher.java) |
| Outbox row shape | `OutboxEvent` entity | [OutboxEvent.java](order-service/src/main/java/com/example/cdc/order/domain/OutboxEvent.java) |
| DB schema + replica identity | Flyway `V2__outbox_events.sql` | [V2__outbox_events.sql](order-service/src/main/resources/db/migration/V2__outbox_events.sql) |
| Cleanup | `@Scheduled OutboxCleanupJob` | [OutboxCleanupJob.java](order-service/src/main/java/com/example/cdc/order/service/OutboxCleanupJob.java) |
| Error surface | `GlobalExceptionHandler` | [GlobalExceptionHandler.java](order-service/src/main/java/com/example/cdc/order/web/GlobalExceptionHandler.java) |
| Metrics | Micrometer + Actuator | `application.yml` |

### How each sub-problem is answered
- **Dual-write** — `@Transactional` on `OrderService`. Both inserts share one commit.
  If either throws, the entire TX rolls back and neither row exists.
- **Where events leave the process** — they don't. They leave the *database*, via
  Debezium. The JVM only writes DB rows.
- **Outbox row shape matches the SMT defaults** — column names and types line up
  1:1 with `transforms.outbox.table.field.event.*` in the connector config; the SMT
  needs zero custom code on our side.
- **DELETEs on cleanup don't leak** — connector sets `tombstones.on.delete=false`,
  the SMT only emits messages for INSERT events (its default), and the outbox row
  UUID is the *stable* dedup key so even a hypothetical duplicate is a no-op at the
  consumer.
- **Producer keeps working when Kafka is down** — because it doesn't talk to Kafka.
  The DB is the only synchronous dependency.

### Tech debt to acknowledge
- **Outbox retention is time-based, not capture-based.** If Debezium is down longer
  than the retention window, we lose events. Correct: monitor the replication slot
  lag and *alert* well before it grows into 7 days. A capture-based delete (mark on
  capture, prune later) would remove the need for the alert but requires an
  application read of a Debezium topic — a coupling we're avoiding.
- **`OrderService.markPaid / cancel` emit `OrderCreated`-shape payload.** The payload
  DTO is the same shape for all event types; a subscriber that cared about `paidAt`
  vs. `cancelledAt` would need a richer type or per-event payload. For a PoC the
  event_type header is enough.
- **`Jackson` serialization can throw at insert time.** We wrap it in
  `IllegalArgumentException` which the global handler maps to 500. A production
  producer would validate up front, not at the JSON write.
- **No idempotency key on the HTTP layer.** Two identical `POST /api/orders` calls
  become two distinct orders. If the caller retries the same request they'll get
  two orders and two events. Adding an `Idempotency-Key` header is a client-facing
  concern that the outbox pattern does not solve.
- **JPA `Persistable`-based inserts** avoid the SELECT-before-INSERT round trip, but
  the pattern is brittle — forget the `@PostPersist markNotNew()` and every save
  becomes an UPDATE-shaped no-op.

---

## Component #2 — Debezium + Outbox Event Router (the CDC layer)

**Source:** Postgres WAL, table `public.outbox_events`.
**Target:** Kafka topics named `outbox.event.<aggregate_type>`.

### What makes it hard
- The producer never writes to Kafka, so we can't put the delivery guarantee in the
  application. It has to live in the streaming layer.
- The consumer expects clean business events, not Debezium change envelopes
  (`{"before":..., "after":..., "source":..., "op":"c"}`).
- We need per-aggregate ordering — the Kafka key has to be `aggregate_id`, not the
  row UUID.
- We must **not** emit Debezium messages for the cleanup job's DELETEs.
- Postgres logical replication is single-connector-per-slot. Scaling the connector
  is a distinct problem (see [CONSISTENCY.md](CONSISTENCY.md)).

### What we are protecting
- **The wire format**: a clean business event with `id` and `type` headers and the
  raw JSON payload as the value. No Debezium envelope.
- **Per-aggregate ordering**: Kafka's per-partition ordering is meaningful iff the
  key is stable per aggregate; `aggregate_id` is that key.
- **The replication slot**: it is the checkpoint. If we lose it we replay from the
  beginning (or nothing, depending on `snapshot.mode`).

### Solution shape
- Debezium Connect runs as a container with `heartbeat.interval.ms=10000` so idle
  slots don't stall WAL reclamation.
- The connector uses the built-in `pgoutput` plugin (no wal2json install needed on
  the Postgres image).
- `table.include.list=public.outbox_events` narrows CDC to the outbox — the `orders`
  table is *not* captured.
- `snapshot.mode=no_data` — we don't want a snapshot of pre-existing outbox rows on
  first start. New rows only.
- The Outbox Event Router SMT (`io.debezium.transforms.outbox.EventRouter`) does the
  reshaping: pulls `id`, `aggregate_id`, `event_type`, `payload`, `created_at` out of
  the Debezium envelope, sets the Kafka key to `aggregate_id`, routes to
  `outbox.event.${aggregate_type}`, and preserves the row UUID as the `id` header.
- `transforms.outbox.table.expand.json.payload=true` unpacks the JSONB column so the
  Kafka value is the payload object, not a JSON-encoded string.
- `tombstones.on.delete=false` prevents the cleanup DELETEs from turning into null-value
  tombstones on the wire.

### Key tech by responsibility
| Responsibility | Component |
|----------------|-----------|
| Read the WAL | Debezium `PostgresConnector` + `pgoutput` |
| Reshape to business event | `io.debezium.transforms.outbox.EventRouter` SMT |
| Route to per-aggregate topic | `route.by.field=aggregate_type` + `route.topic.replacement` |
| Preserve the outbox row UUID | `additional.placement=event_type:header:type` + default `id` header |
| Keep the slot alive when idle | `heartbeat.interval.ms=10000` |
| Suppress cleanup DELETEs | `tombstones.on.delete=false` (SMT emits on INSERT by default) |
| Storage of connector state | Kafka topics (`debezium_connect_configs / offsets / statuses`) |

See [debezium-config/outbox-connector.json](debezium-config/outbox-connector.json)
for the full configuration.

### How each sub-problem is answered
- **Kafka message shape** — Outbox SMT extracts the payload column and sets it as
  the message value verbatim. Consumers see plain business JSON.
- **Per-aggregate ordering** — `aggregate_id` is the Kafka key. Kafka's default
  partitioner hashes it, so all events for one order land on one partition.
- **Message id for idempotency** — the outbox row UUID lands in the `id` header,
  independent of Kafka's own offset. That is what the consumer dedups on.
- **Replication-slot bloat while consumer is silent** — heartbeat frames advance
  the confirmed flush LSN even when no rows are captured.
- **Wire format contract** — the JSON payload column is what the producer wrote;
  the SMT does not add fields. If we change the payload shape we do it in the
  producer's DTO, not in the connector.

### Tech debt to acknowledge
- **`tasks.max=1`.** The Postgres connector is single-task by design (one
  replication slot per connector). Scaling this out is not "raise tasks.max" — it
  is a whole HA discussion documented in [CONSISTENCY.md](CONSISTENCY.md).
- **JSON on the wire, no schema.** A field rename in the producer's payload silently
  breaks consumers. A Schema Registry + Avro would catch this at emit time.
- **Snapshot mode `no_data` assumes the outbox is empty at first launch.** If we
  register the connector after production traffic has already accumulated outbox
  rows, those rows are silently ignored.
- **The connector's storage topics are unreplicated in this PoC.** The compose file
  runs a single-broker cluster; a real deployment must replicate the connector's
  config/offsets/status topics to survive broker loss.
- **Retention on the source topic `outbox.event.Order` is broker-default.** Auto-topic
  creation with default retention (usually 7 days) can silently drop old messages;
  replay-from-beginning tests must account for that.
- **`publication.autocreate.mode=filtered`** creates the Postgres publication for us.
  Convenient in dev, but production teams often prefer DBA-managed publications and
  set this to `disabled`.

---

## Component #3 — `notification-service` (the consumer)

**Source:** Kafka topic `outbox.event.Order` (name is configurable).
**Target:** log a "notification" side effect. In real life this would be email/SMS/push,
a downstream API call, or a webhook.

### What makes it hard
- Kafka is **at-least-once**. Rebalances, offset-commit races, and manual replay all
  redeliver messages.
- The dedup key is not the topic/partition/offset (those are unstable under replay) —
  it's a stable header the producer supplied.
- A poison message must not stall the partition forever, and must not vanish silently.
- Multiple consumer instances (during a rolling deploy or under load) may race to
  process the same event id. The DB has to break the tie.
- A deserialisation failure is different from a business failure — one is a permanent
  bad record, the other is worth retrying.

### What we are protecting
- **Exactly-once side effect** on each event id: the "notification" is sent zero times
  if the row is a duplicate, exactly once otherwise.
- **Progress**: no partition stalls forever on a poison message.
- **Auditability**: every skipped-because-duplicate message shows up in the log; every
  poison message shows up on the DLT.

### Solution shape
- `OrderEventConsumer` is a `@KafkaListener` with `MANUAL_IMMEDIATE` ack mode. It reads
  the `id` and `type` headers, calls the service, then acks.
- `NotificationService.handle(eventId, ...)` is `@Transactional`. It:
  1. checks `processedRepository.existsById(eventId)`; if present → skip;
  2. inserts a `ProcessedEvent` row (PK = eventId) in this same TX;
  3. dispatches the side effect.
- `ProcessedEvent` implements `Persistable<UUID>` so `save()` skips the wasted
  SELECT-before-INSERT Spring Data would otherwise do.
- `KafkaConfig`:
  - `ErrorHandlingDeserializer` wraps `JsonDeserializer` so a bad JSON payload throws
    a deserialisation exception that the error handler *can catch* — rather than
    NPEing later.
  - `DefaultErrorHandler` retries with `ExponentialBackOff(1000ms, 2.0)`, capped at
    30s per attempt, capped at 120s total elapsed. After exhaustion the record goes
    to `<topic>.DLT` via `DeadLetterPublishingRecoverer` and the offset is committed.
- `DltConsumer` subscribes to `outbox.event.Order.DLT` and logs the original topic,
  key, offset, and exception message — this is our human-visible signal that a
  poison message landed.

### Key tech by responsibility
| Responsibility | Component | File |
|----------------|-----------|------|
| Subscribe + ack | `OrderEventConsumer` (`@KafkaListener`, `MANUAL_IMMEDIATE`) | [OrderEventConsumer.java](notification-service/src/main/java/com/example/cdc/notification/consumer/OrderEventConsumer.java) |
| Idempotent dispatch | `@Transactional NotificationService` | [NotificationService.java](notification-service/src/main/java/com/example/cdc/notification/service/NotificationService.java) |
| Dedup ledger | `processed_events` table + `ProcessedEvent` entity | [ProcessedEvent.java](notification-service/src/main/java/com/example/cdc/notification/domain/ProcessedEvent.java) |
| Retry + DLT | `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` | [KafkaConfig.java](notification-service/src/main/java/com/example/cdc/notification/config/KafkaConfig.java) |
| Bad-payload handling | `ErrorHandlingDeserializer` around `JsonDeserializer` | [KafkaConfig.java](notification-service/src/main/java/com/example/cdc/notification/config/KafkaConfig.java) |
| DLT observability | `DltConsumer` | [DltConsumer.java](notification-service/src/main/java/com/example/cdc/notification/consumer/DltConsumer.java) |

### How each sub-problem is answered
- **At-least-once → exactly-once side effect** — `existsById` first, PK insert in the
  same TX as the dispatch. If two instances race, one wins the PK; the loser's TX
  rolls back with a `DataIntegrityViolationException`; the error handler retries the
  loser's poll; on retry the second attempt sees `existsById=true` and no-ops.
- **Per-aggregate ordering** — the SMT already keyed on `aggregate_id`, so the
  consumer sees events for one order in commit order without any extra logic.
- **Poison messages** — `ErrorHandlingDeserializer` converts a JSON error into a
  handled exception; `DefaultErrorHandler` retries with backoff; the DLT catches
  what retries can't fix; `DltConsumer` surfaces the failure in logs.
- **Rebalances** — offsets are only committed after the transactional handler
  returns, so a rebalance mid-batch re-delivers un-processed records. Idempotency
  absorbs the duplicates.
- **Bounded blast radius** — `max.poll.records=50` keeps the batch small so a bad
  record's retry does not hold a very large group of records hostage.

### Tech debt to acknowledge
- **`existsById + insert` is not a single atomic check under contention across two
  processes.** The PK insert is what actually enforces exactly-once; the `existsById`
  is a fast path to avoid throwing on every duplicate. If two instances race, one
  gets a `DataIntegrityViolationException` — currently mapped through the error
  handler to a retry-then-success. Fine, but produces one confusing stack trace per
  race.
- **`processed_events` grows unboundedly.** No TTL. Same shape of problem as
  `outbox_events` on the producer side; needs a retention window keyed on
  `processed_at`, and the window must be longer than the maximum plausible replay.
- **The "notification" side effect is a log line.** A real dispatch (HTTP call,
  email, SMS) has its own idempotency story — dedup at our layer is necessary but
  not sufficient. The downstream must also tolerate exact-copy retries.
- **`spring.json.trusted.packages` is set to our own DTO package.** Broadening it
  weakens deserialisation safety; keep it tight.
- **No consumer-side rate limit.** A cold-start replay of the whole topic backlog
  will hammer the DB dedup table until it catches up. Set `max.poll.records` and
  `fetch.max.bytes` before running the first big replay.
- **Retry policy is time-bounded (`maxElapsedTime=120s`), not attempt-bounded.**
  A record that fails on a 45s DB blip gets 2–3 tries then hits the DLT — not
  necessarily what we want. Tune per environment.

---

## Cross-cutting: what the three components share (and don't)

These aren't "one component's tech" — they are the reasons the pipeline stays coherent
across restarts, redeploys, and rebalances.

### The `id` header — the one contract across all three
The **outbox row UUID** is produced by the JVM, written to Postgres, captured by
Debezium, placed on the Kafka message as the `id` header, and used by the consumer
as the PK of its dedup ledger. Every component that touches it treats it as
opaque bytes. It is the reason exactly-once effect is possible.

### `aggregate_id` — the per-order ordering key
Written by the producer as a column; propagated by the SMT into the Kafka message
key; used by Kafka's default partitioner. Consumers get commit-order per aggregate
for free.

### The absence of `KafkaTemplate` in the producer
This is deliberate. If the producer never sees the broker, there is no way for a
"forgot to send" bug to exist. All of `order-service`'s Kafka behaviour lives in
the Debezium config.

### The two databases
`cdc` (holds `orders` and `outbox_events`) and `notifications` (holds
`processed_events`). They are separate on purpose — the consumer's dedup ledger must
be able to survive a source-DB rewind and vice versa. Same cluster is fine; same
schema is not.

### Metrics
Both services expose `/actuator/prometheus`. The producer publishes HTTP + DB
timers; the consumer publishes Kafka consumer lag + DB timer. In this PoC we don't
ship a Grafana dashboard, but the metric names line up with the Micrometer defaults
Spring publishes, so a `KafkaListener` name is enough to build one.

---

## Global tech debt (applies to the whole POC)

- **One Kafka broker, one Debezium task.** Any HA at all requires re-thinking. See
  [CONSISTENCY.md](CONSISTENCY.md) for the shape of the change; the code does not
  need to change to run in a multi-broker cluster, only the compose file.
- **No auth on `/api/orders/*` or `/actuator/*` (except health-details are gated).**
  Front with Spring Security or a network policy before exposing.
- **JSON on the wire, no schema registry.** A field rename in the producer silently
  breaks the consumer; schema evolution is not solved here.
- **Outbox `REPLICA IDENTITY FULL` is heavier than PK-only** — every INSERT writes
  every column to the WAL. Fine for a low-cardinality table like `outbox_events`;
  a bad idea on a hot business table.
- **No end-to-end tracing.** A correlation id would let ops follow one order from
  HTTP request to notification log line. We rely on the outbox UUID for that today.
- **Cleanup DELETEs still hit the WAL.** The connector suppresses them from
  Kafka but they are still WAL bytes that Debezium must skip past. On very high
  traffic this is measurable.
- **The consumer's `existsById` check is per-record.** With batching, a "seen-set"
  cache would cut DB round-trips at the cost of memory. Skipped in the PoC.
- **`Persistable<UUID>` on entities skips the read-before-write** — but forgetting
  `@PostPersist` on a new entity type re-introduces the wasted SELECT. Easy footgun.
- **Configuration lives in three places.** `application.yml` (services), the compose
  environment block (containers), and `outbox-connector.json` (Debezium). Every prod
  rollout has to align all three.
