# db-to-es-sync-poc

> **The #1 ES production headache: keeping Postgres and ES in sync.**
> Three strategies in one app, side-by-side, with deliberate drift scenarios you can trigger.

## What this POC shows

Same domain (a `products` table), three completely separate sync paths that all index into Elasticsearch:

| Path | Code path | Index alias | Failure mode it has |
|---|---|---|---|
| **Dual-write** (naive) | `POST /api/v1/sync/naive/products` | `products_naive` | DB-commits-but-ES-fails → permanent drift |
| **Transactional outbox + Kafka → ES** | `POST /api/v1/sync/outbox/products` | `products_outbox` | None at steady state. Some lag. |
| **Debezium CDC (embedded) → ES** | `POST /api/v1/sync/cdc/products` | `products_cdc` | None at steady state. Replication slot can fill disk if consumer dies. |

The endpoints write to *separate schemas* (`sync_naive`, `sync_outbox`, `sync_cdc`) so you can compare DB↔ES state per strategy independently.

Each strategy can also be **broken on purpose** via headers:
- `X-Inject-Failure: es-fail` — make the ES write throw (only affects dual-write meaningfully)
- `X-Inject-Failure: kafka-fail` — make Kafka publish throw (affects outbox)
- `X-Inject-Failure: db-rollback` — make the transaction roll back after the dual-write call but before commit

The goal: see exactly how each strategy reacts.

## Run it

```bash
docker compose --profile sync up -d
mvn spring-boot:run -pl db-to-es-sync-poc
```

Then:
```bash
# Scenario 1: happy path on all three strategies
./scripts/demo.ps1 happy-path

# Scenario 2: dual-write under ES failure
./scripts/demo.ps1 dual-write-drift

# Scenario 3: outbox: kill the indexer mid-flight
./scripts/demo.ps1 outbox-recovery

# Scenario 4: CDC: see WAL → ES propagation
./scripts/demo.ps1 cdc-flow
```

## What you should see

### Happy path
All three: 100 products in Postgres, 100 in their respective ES index. Outbox table empty after drain. CDC offset advanced past the last LSN.

### Dual-write with `X-Inject-Failure: es-fail`
Postgres has the new row. ES does not. Forever. There is *no recovery mechanism* in the dual-write path — that's the whole point. Compare against the outbox path with the same failure injected: outbox rows accumulate, the indexer retries them, eventually drains.

### Outbox: kill the indexer
1. Send a write while indexer is up → ES has it.
2. `POST /admin/indexer/stop` (or just `Ctrl+C` the embedded poller via `/admin/outbox/poller/pause`).
3. Send 10 more writes → Postgres has them, outbox has 10 rows, ES does not.
4. Resume indexer → outbox drains to zero, ES catches up. Lag visible at `/admin/outbox/stats`.

### CDC
1. Write to Postgres.
2. Watch `/admin/cdc/offset` — LSN advances.
3. ES picks it up within ~1s. Compare against outbox lag (typically slower due to extra hop).

## What's in here

```
db-to-es-sync-poc/
├── README.md (this file)
├── ARCHITECTURE.md (sequence diagrams)
├── pom.xml
├── src/main/java/com/example/espoc/sync/
│   ├── Application.java
│   ├── config/
│   │   ├── DataSourceConfig.java
│   │   ├── JpaConfig.java
│   │   ├── KafkaConfig.java
│   │   └── DebeziumConfig.java
│   ├── model/
│   │   ├── Product.java                (JPA entity)
│   │   ├── OutboxEvent.java            (JPA entity)
│   │   └── dto/ProductDto.java
│   ├── repository/
│   │   ├── NaiveProductRepository.java
│   │   ├── OutboxProductRepository.java
│   │   ├── CdcProductRepository.java
│   │   └── OutboxEventRepository.java
│   ├── strategy/
│   │   ├── naive/
│   │   │   └── DualWriteSyncService.java
│   │   ├── outbox/
│   │   │   ├── OutboxSyncService.java
│   │   │   ├── OutboxPoller.java
│   │   │   ├── OutboxProducer.java
│   │   │   └── OutboxConsumer.java
│   │   └── cdc/
│   │       ├── CdcSyncService.java
│   │       └── DebeziumEngineRunner.java
│   ├── es/
│   │   └── ProductEsIndexer.java
│   ├── controller/
│   │   ├── SyncDemoController.java
│   │   └── AdminController.java
│   └── support/
│       └── FailureInjector.java
├── src/main/resources/
│   ├── application.yml
│   ├── db/migration/
│   │   ├── V1__create_naive.sql
│   │   ├── V2__create_outbox.sql
│   │   └── V3__create_cdc.sql
│   └── es/products-mapping.json
└── scripts/demo.ps1
```

## Anti-patterns explicitly demonstrated

1. **The dual-write is intentionally inside `@Transactional`** so you can see how ES-call-throws-inside-tx still doesn't help.
2. **The outbox poller intentionally has no leader election** — works for single-instance, would race in a multi-instance deploy. Comments mark where to wire `pg_try_advisory_lock`.
3. **The CDC path uses Debezium embedded engine**, not Connect — simpler to demo, but documented tradeoff: embedded engine has weaker fault tolerance than Connect.
