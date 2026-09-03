# Kafka Production Patterns POC

A multi-module Spring Boot 3.4 / Java 21 reference project for the patterns that decide whether your Kafka deployment survives production. Built against **Kafka 4.0 (KRaft)**, **Oracle 23ai Free**, **Elasticsearch 8.15**, **Confluent Schema Registry 7.7**, and **Kafka Connect with Debezium**.

Each module is a runnable Spring Boot app focused on one pattern. The code is heavily commented to explain *why* each setting matters, not just what it does.

---

## Documentation map

Start here depending on what you want:

| Doc | Answers |
|---|---|
| **[ISSUE.md](ISSUE.md)** | *Why does this repo exist?* The core problem, what we're protecting, and the sub-issue each module isolates. |
| **[TECHNICAL.md](TECHNICAL.md)** | *How is each POC engineered?* Per module: the hard problem, solution shape, key tech **by responsibility**, how it solves each sub-problem, and the tech debt we knowingly accept. |
| **[CONSISTENCY.md](CONSISTENCY.md)** | *What happens when I scale?* How every guarantee behaves across N Kubernetes pods / VMs — partition ceilings, stable identity, rebalance survival, shared-state boundaries. |
| **[docs/index.html](docs/index.html)** | *Show me.* A self-contained interactive page — the 12 patterns, an animated order lifecycle, a delivery-semantics simulator, the dual-write/outbox comparison, and a scaling-law visualizer. Open it in any browser. |
| **README.md** (this file) | *How do I run it?* Setup, ports, per-module curl commands, reproducible gotchas. |

---

## TL;DR — start everything

```bash
./scripts/setup.sh                            # Brings up infra, creates topics
./mvnw clean install -DskipTests              # Builds all modules

# Run any module:
./mvnw -pl 06-outbox-pattern spring-boot:run
```

| Module | Port | Pattern |
|---|---|---|
| 01-idempotent-producer | 8101 | `enable.idempotence`, `acks=all`, ordering guarantees |
| 02-transactions | 8102 | Read-process-write with `sendOffsetsToTransaction` |
| 03-offset-management | 8103 | Auto vs manual commit, sync vs after-processing, idempotent consumer |
| 04-dlq-poison-message | 8104 | `@RetryableTopic`, exponential backoff, DLQ replay |
| 05-rebalancing-backpressure | 8105 | Static membership, cooperative-sticky, pause/resume |
| 06-outbox-pattern | 8106 | Transactional Outbox with Oracle + `SKIP LOCKED` poller |
| 07-saga-orchestration | 8107 | Choreography saga with compensation |
| 08-cqrs-projection | 8108 | Event-driven projection into Elasticsearch |
| 09-kafka-streams-windowing | 8109 | Tumbling, hopping, session windows |
| 10-kafka-streams-joins | 8110 | KStream-KTable, GlobalKTable, KStream-KStream temporal joins |
| 11-schema-registry-avro | 8111 | Avro + Schema Registry + evolution rules |
| 12-cdc-pipeline | — | Debezium Oracle → Kafka → ES sink (Connect configs) |

---

## Infrastructure

```bash
docker compose --profile core up -d           # Kafka + Schema Registry + UI
docker compose --profile data up -d           # + Oracle + Elasticsearch + Kibana
docker compose --profile connect up -d        # + Kafka Connect with Debezium
docker compose --profile observability up -d  # + Prometheus + Grafana
docker compose --profile all up -d            # Everything
```

| Service | URL |
|---|---|
| Kafka UI | http://localhost:8080 |
| Schema Registry | http://localhost:8081 |
| Kafka Connect REST | http://localhost:8083 |
| Kibana | http://localhost:5601 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (anonymous Admin) |
| Oracle | `jdbc:oracle:thin:@localhost:1521/FREEPDB1` (`appuser` / `AppUser123`) |
| Elasticsearch | http://localhost:9200 |

> First Oracle startup takes ~3 minutes while the DB initializes archive logs. Wait for `oracle` to report healthy before running module 06+.

---

## The lessons each module proves

### 1. Idempotent Producer — the floor for any safe producer

```bash
curl -X POST 'http://localhost:8101/demo/safe?count=10000'
curl -X POST 'http://localhost:8101/demo/unsafe?count=10000'
```

- `acks=all` + `enable.idempotence=true` gives you no duplicates *per producer session*, and no silent loss on leader failure.
- `max.in.flight.requests.per.connection=5` is the upper bound the idempotent producer guarantees ordering for.
- The unsafe producer uses `acks=1` and `enable.idempotence=false` — count what arrives downstream and you'll see ordering breaks and duplicates appear under any broker hiccup.

### 2. Transactions — actual exactly-once across topics

The read-process-write loop starts automatically when this module boots. It reads `orders.placed.v1`, fans out atomically to `orders.paid.v1` + `shipping.requested.v1`, and commits the consumer offset through `sendOffsetsToTransaction`. A `read_committed` consumer never sees half-written batches.

Key production gotchas:

- The transactional ID must be stable per producer instance — derive from `StatefulSet` ordinal or pod hostname.
- Catching `ProducerFencedException` and *shutting down* is the only correct response. A zombie producer that retries will corrupt offsets.

### 3. Offset Management — the most important POC

```bash
curl -X POST 'http://localhost:8103/offsets/start?mode=AUTO&crashAfter=50'
curl -X POST 'http://localhost:8103/offsets/start?mode=SYNC_BEFORE&crashAfter=50'
curl -X POST 'http://localhost:8103/offsets/start?mode=SYNC_AFTER&crashAfter=50'
curl -X POST 'http://localhost:8103/offsets/start?mode=IDEMPOTENT_AFTER&crashAfter=50'
curl 'http://localhost:8103/offsets/stats'
```

Modes compared:

| Mode | On crash mid-processing | Notes |
|---|---|---|
| `AUTO` | Offset is committed but work didn't complete → **message lost** | What the Quickstart teaches you |
| `SYNC_BEFORE` | Same loss as AUTO — committing before work always loses on crash | Common anti-pattern |
| `SYNC_AFTER` | **At-least-once**: replay on restart | Requires idempotent handler |
| `IDEMPOTENT_AFTER` | At-least-once + DB-backed dedup table | Closest to true exactly-once side effects |

### 4. DLQ + Poison-Message handling

```bash
# Produce a mix of normal / FAIL / POISON messages
./scripts/produce-test-data.sh 1000
# Inspect DLQ contents
curl http://localhost:8104/dlq/peek?max=20
# Replay 100 from DLQ
curl -X POST 'http://localhost:8104/dlq/replay?max=100'
```

- `@RetryableTopic` + non-blocking retries means a slow customer never stalls the rest of the partition.
- Throwing `PoisonMessageException` short-circuits straight to the DLQ.
- `@DltHandler` is where alerting, archival, and forensics belong.
- The replay endpoint is what on-call actually needs at 3 AM — not a redeploy.

### 5. Rebalancing + Backpressure

```bash
# Inject latency on the slow consumer
curl -X POST 'http://localhost:8105/rebalance/delay?ms=200'
curl 'http://localhost:8105/rebalance/stats'
```

- Static membership (`group.instance.id`) means rolling deploys don't trigger rebalance for short restarts.
- Cooperative-sticky assignor cuts pause durations from seconds (eager) to near-zero.
- `pause()` / `resume()` lets you apply backpressure without leaving the group. Keep calling `poll()` so `max.poll.interval.ms` doesn't kick you out.

### 6. Transactional Outbox — the fix for the dual-write problem

```bash
curl -X POST 'http://localhost:8106/orders?customerId=cust-1&amount=99.99'
curl -X POST 'http://localhost:8106/orders?fail=true'   # Whole transaction rolls back
curl 'http://localhost:8106/orders/stats'
```

- The `orders` insert and the `outbox` insert happen in the *same Oracle transaction*. Either both commit or neither does — there is no scenario where the order exists but the event is missing.
- The poller uses `FOR UPDATE SKIP LOCKED` so multiple instances can run in parallel without colliding.
- For zero-latency CDC, replace the poller with Debezium (module 12).

### 7. Saga choreography

```bash
curl -X POST 'http://localhost:8107/saga/orders?sku=SKU-001&qty=2'
curl -X POST 'http://localhost:8107/saga/failure-rate?rate=0.5'   # Force compensation
curl 'http://localhost:8107/saga/inventory'
```

- Happy path: `OrderPlaced → InventoryReserved → PaymentCompleted → ShippingScheduled`.
- Failure path: `PaymentFailed` triggers two compensating actions in parallel — inventory releases the reserve, order is marked `CANCELLED`.
- Each service updates its own DB row inside a Spring `@Transactional` boundary and emits the next event. No central orchestrator.

### 8. CQRS Projection into Elasticsearch

```bash
# Trigger orders via module 06 or 07, then query:
curl 'http://localhost:8108/orders/by-customer?id=cust-1'
curl 'http://localhost:8108/orders/by-status?status=SHIPPED'
```

- Read model is denormalized for the query, not for storage.
- Out-of-order delivery handled by comparing `occurredAt` before applying updates.
- `docAsUpsert=true` lets event handlers be order-independent: a `Shipped` event can land before its `Placed` and the index still converges.

### 9–10. Kafka Streams

- Three windowing flavors with explicit grace periods — without them, late records are silently dropped.
- `KStream-KTable` requires co-partitioning. If keys differ, you MUST `repartition()` or the join is silently wrong.
- `GlobalKTable` for low-cardinality reference data — replicated to every task, no co-partitioning.
- `KStream-KStream` temporal joins for funnels and attribution.
- `processing.guarantee=exactly_once_v2` + `num.standby.replicas=1` is the production default.

### 11. Schema Registry + Avro

```bash
curl -X POST 'http://localhost:8111/avro/v2?withAddress=false'
curl -X POST 'http://localhost:8111/avro/v2?withAddress=true'
```

- The `OrderEvent` schema includes a v2 `shippingAddress` field as `["null", Address]` with `default: null` — that's the canonical *backward compatible* evolution pattern.
- For production: `auto.register.schemas=false` and register via CI gate.
- `specific.avro.reader=true` deserializes to the generated class instead of `GenericRecord`.

### 12. CDC pipeline

Two connectors do the entire pipeline with no application code:

- **Debezium Oracle source** with the **Outbox Event Router SMT** turns one outbox table into per-aggregate topics.
- **Elasticsearch sink** with idempotent upsert by document id, DLQ topic for malformed records.

See `12-cdc-pipeline/README.md` for the production checklist.

---

## Production gotchas covered across the modules

| Trap | Where it bites | Fix in this repo |
|---|---|---|
| `acks=1` + leader failure | Silent message loss | Module 01: `SafeProducerProps` defaults to `acks=all` + idempotent |
| Auto-commit + crash mid-processing | Lost messages | Module 03: side-by-side demo |
| Eager rebalance during deploys | Multi-second consumer pauses | Module 05: cooperative-sticky + static membership |
| `max.poll.interval.ms` exceeded | Repeated rebalance storms | Module 05: backpressure + pause/resume |
| Dual-write Kafka + DB | Ghost or lost events | Module 06: Transactional Outbox |
| Schema "harmless" rename | Consumers explode | Module 11: backward compat enforced by registry |
| Topic auto-create | Wrong partition count and retention | `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false` in docker-compose |
| Out-of-order across topics | Read model corruption | Module 08: `occurredAt` comparison before apply |
| Hot partition from low-cardinality key | One slow consumer | Module 01: ordering demo + producer config for sticky partitioner |
| Long processing → consumer kicked | Re-delivery loop | Module 05: pause/resume + bounded `max.poll.records` |
| Mock-based tests passing while migration broke | Production surprise | Testcontainers in test scope for every module |

---

## What's deliberately missing (and where to extend)

- **MirrorMaker 2** for DR / multi-region — config-only, not a Spring app.
- **Throughput tuning rig** (`linger.ms` / `batch.size` / `compression.type` ablation) — module 01 has the knobs; add a JMH harness if you want numbers.
- **Burrow / kafka-lag-exporter Grafana dashboards** — the metrics scrape config is wired up; add JSON dashboards under `infra/grafana/dashboards/`.

---

## Layout

```
.
├── docker-compose.yml          # Kafka 4.x + SR + Connect + Oracle + ES + Prometheus
├── pom.xml                     # Multi-module parent
├── common/                     # Shared event envelope, safe configs, metrics
├── infra/                      # Oracle init SQL, Prometheus config
├── scripts/                    # setup, create-topics, register-connectors, produce-test-data
├── 01-idempotent-producer/
├── 02-transactions/
├── 03-offset-management/
├── 04-dlq-poison-message/
├── 05-rebalancing-backpressure/
├── 06-outbox-pattern/
├── 07-saga-orchestration/
├── 08-cqrs-projection/
├── 09-kafka-streams-windowing/
├── 10-kafka-streams-joins/
├── 11-schema-registry-avro/
└── 12-cdc-pipeline/            # Connect configs only
```

---

## Bonus: Kafka gotchas you can reproduce here

1. **"Lost" messages from `acks=1` + leader failure** → module 01, `/demo/unsafe`.
2. **Re-delivery during rebalance from commit-before-processing** → module 03, mode `SYNC_BEFORE`.
3. **Hot partition from low-cardinality key** → module 01, send 10k records with key `"a"`.
4. **Consumer kicked from group due to long processing** → module 05, set delay to 60s.
5. **Schema compatibility broken by "harmless" field rename** → module 11, edit the .avsc to rename `orderId` and rebuild.
6. **Topic deletion not actually deleting** → set `auto.create.topics.enable=true` and re-consume from the deleted topic.
7. **`__consumer_offsets` exploding from too many groups** → spin up many modules with unique group IDs and watch the topic grow.
8. **Out-of-order delivery within a single partition** → can't reproduce, that's the guarantee. Cross-partition though — module 08 handles it.
