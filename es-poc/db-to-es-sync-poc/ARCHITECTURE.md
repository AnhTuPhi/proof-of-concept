# db-to-es-sync-poc — Architecture

## Three pipelines coexist

```
                            ┌──────────────────────────────────────┐
                            │           Spring Boot app             │
                            │                                       │
HTTP write ─────────────────┤                                       │
                            │   ┌────────────┐                      │
   /naive/products  ──────► │   │ NaiveSvc   │ ─► PG.sync_naive ─┐ │
                            │   │ (dual-wr)  │                   │ │
                            │   └─────┬──────┘                   │ │
                            │         │ (same call)              │ │
                            │         └──────────► ES "products_naive"
                            │                                       │
                            │   ┌────────────┐                      │
   /outbox/products ──────► │   │ OutboxSvc  │ ─► PG.sync_outbox    │
                            │   │ (tx outbox)│        + outbox      │
                            │   └────────────┘                      │
                            │                                       │
                            │   ┌────────────┐ poll  ┌────────┐     │
                            │   │OutboxPoller├──────►│Producer│ ────┼─► Kafka
                            │   └────────────┘       └────────┘     │   topic: outbox.products
                            │                                       │
                            │   ┌────────────┐                      │
                            │   │OutboxCons. │◄─────────────────────┼── Kafka
                            │   └─────┬──────┘                      │
                            │         └──────────► ES "products_outbox"
                            │                                       │
                            │   ┌────────────┐                      │
   /cdc/products    ──────► │   │ CdcSvc     │ ─► PG.sync_cdc       │
                            │   │ (plain JPA)│        (WAL)         │
                            │   └────────────┘         │            │
                            │                          ▼            │
                            │   ┌──────────────────────────┐        │
                            │   │ Debezium embedded engine │        │
                            │   │ tails PG WAL             │        │
                            │   └──────────┬───────────────┘        │
                            │              ▼                        │
                            │   ┌──────────────────────────┐        │
                            │   │ EsApplier (direct → ES)  │        │
                            │   └──────────┬───────────────┘        │
                            │              └──► ES "products_cdc"   │
                            └──────────────────────────────────────┘
```

The three pipelines are entirely separate so a bug in one cannot mask a behavior in another.

## Naive: dual-write inside a transaction

```
HTTP POST
   │
   ▼
@Transactional
   ├─► JPA save(product)         (DB: pending commit)
   ├─► esClient.index(product)   (ES: applied immediately — refresh=false but durable)
   └─► (transaction commits)
```

Failure cases:
- **ES throws** → exception propagates → DB rolls back. ES has nothing because the call failed.
  - *But*: if the ES call partially succeeded (e.g. it succeeded on the primary but the response was lost), ES has the doc while DB rolls back.
- **ES succeeds, then commit fails** (e.g. constraint violation triggered on flush) → DB rolls back, ES has the doc.
- **ES succeeds, then process killed before commit** → DB rolls back on connection drop, ES has the doc.

You cannot fix these inside the dual-write pattern. That's the structural problem.

## Outbox: transactional outbox + Kafka

```
HTTP POST
   │
   ▼
@Transactional
   ├─► JPA save(product)
   └─► JPA save(OutboxEvent{aggregate_id, payload, created_at})
   (both commit atomically — same row in pg_xact)

Then asynchronously, OutboxPoller:
   ├─► SELECT * FROM outbox WHERE picked_up_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED
   ├─► For each: kafkaTemplate.send("outbox.products", aggregateId, payload)
   └─► UPDATE outbox SET picked_up_at=now() WHERE id IN (...)

Then OutboxConsumer:
   ├─► Kafka message arrives
   ├─► esClient.index(payload)  (idempotent upsert by aggregate_id)
   └─► offset commit (after ES write succeeds)
```

Failure cases — all handled:
- **App crashes after DB commit, before poller picks up** → row sits in outbox, picked up after restart.
- **Kafka publish fails** → poller leaves row un-marked, retries on next tick.
- **ES write fails** → Kafka offset *not* committed, message redelivered.

The remaining risk is **duplicate delivery** (at-least-once). We handle it by making the ES write idempotent: same `_id` = same product, overwrite is fine. Out-of-order: handled with `version_type: external` using the product's `updated_at` epoch ms.

## CDC: Debezium embedded engine → ES

```
HTTP POST
   │
   ▼
@Transactional
   └─► JPA save(product)   (app code does nothing else)

Postgres WAL gets the change.

Debezium embedded engine (running in same JVM):
   ├─► tails the replication slot
   ├─► emits ChangeEvent { op: c|u|d, after: {...} }
   └─► EsApplier consumes
       └─► esClient.index(after)
```

Failure cases:
- **App crashes** → engine restarts, picks up from last committed offset (stored in `debezium-offsets.dat`).
- **ES down** → engine pauses, WAL accumulates in replication slot. **Disk-fill risk** if down for long.
- **Schema change** → Debezium handles ALTER TABLE transparently.

## State you can inspect

| Endpoint | Returns |
|---|---|
| `GET /admin/db-vs-es?strategy=naive` | Row counts in both, lists missing/extra IDs |
| `GET /admin/outbox/stats` | Pending count, oldest pending age, last-published id |
| `GET /admin/cdc/offset` | Current Debezium offset / LSN |
| `POST /admin/outbox/poller/pause` | Stop draining the outbox (to simulate operator outage) |
| `POST /admin/outbox/poller/resume` | Restart it |
| `POST /admin/indexer/break` | Make ES indexer return failure for next N writes |

## Where to extend

1. **Per-aggregate ordering** — currently the outbox poller fetches LIMIT 100 in id order. For per-aggregate ordering you'd partition Kafka by aggregate_id (which we do already), so consumers within a partition see ordered events. Good enough.
2. **Multi-instance outbox poller** — would race today. Wire `pg_try_advisory_lock(123)` at the start of the poll method to enforce single-leader.
3. **Debezium via Kafka Connect** — wrap the engine config as a Connect properties file; spin up via `docker compose --profile sync-full`.
