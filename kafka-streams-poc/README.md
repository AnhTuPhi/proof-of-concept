# kafka-streams-poc

Production-ready Kafka Streams POC built on **Spring Boot 3.4 + Java 21**.
Demonstrates the three patterns that make Kafka Streams more than just
produce/consume:

## Docs to read first

| Doc | Purpose |
|---|---|
| [ISSUE.md](ISSUE.md) | The 7 hard problems this POC exists to solve — read for the "why" |
| [TECHNICAL.md](TECHNICAL.md) | Per-POC deep dive — problem, solution shape, key tech, tech debt |
| [CONSISTENCY.md](CONSISTENCY.md) | What breaks (and doesn't) when you scale to N pods on K8s or VMs |
| [demo.html](demo.html) | Standalone interactive explainer — open in a browser |


| Pattern | Where |
|---------|-------|
| **Stream-Table join** (reference data enrichment) | [`OrderEnrichmentTopology`](src/main/java/com/vndirect/kstreams/topology/OrderEnrichmentTopology.java) |
| **Windowed aggregations** (tumbling, hopping, session) | [`WindowedAggregationsTopology`](src/main/java/com/vndirect/kstreams/topology/WindowedAggregationsTopology.java) |
| **Stream-Stream join** (orders ⨝ payments, time-windowed) | [`OrderPaymentJoinTopology`](src/main/java/com/vndirect/kstreams/topology/OrderPaymentJoinTopology.java) |

## Domain

A simplified e-commerce / brokerage event flow:

```
products (compacted KTable)  ─┐
users    (compacted KTable)  ─┤
                              ▼
orders  ──────────────► [enrich] ──► enriched-orders ──► [windowed agg]
                          │                                │
                          │                                ├─► category-revenue   (tumbling 1m)
                          │                                ├─► user-order-counts  (hopping 5m/1m)
                          │                                └─► user-sessions      (session 30s gap)
                          │
                          └─► [order ⨝ payment] ──► completed-orders   (10m sliding window)
payments ────────────────────────────────┘
```

## Production-grade features

- **DLQ poison-pill handler** — `DlqDeserializationExceptionHandler` routes un-parseable records to `streams.dlq.v1` with origin topic/partition/offset/error headers, then `CONTINUE`s so one bad record can't stall the partition.
- **Production exception handler** — `LoggingProductionExceptionHandler` drops only `RecordTooLargeException`, fails fast otherwise.
- **Uncaught exception handler** — `REPLACE_THREAD` so a thread crash doesn't kill the whole app.
- **Compacted reference topics** — `products.v1` / `users.v1` declared with `cleanup.policy=compact`.
- **GlobalKTable lookups** — no co-partitioning headache for reference-data joins.
- **Grace periods** on every window so out-of-order events are still counted.
- **Interactive Queries REST API** — `/api/state/...` reads directly from local state stores.
- **Actuator + Prometheus** — `/actuator/health`, `/actuator/prometheus`, `/actuator/kafkastreams`.
- **Graceful shutdown** + named processor nodes for debuggable topology graphs.
- **TopologyTestDriver** unit tests for every topology — no broker required.

## Prerequisites

- Java 21
- Docker + Docker Compose (for Kafka)
- Maven 3.9+ (or use the included Maven wrapper)

## Run

### 1. Start Kafka (KRaft mode, no Zookeeper)

```bash
docker compose up -d
```

- Kafka broker: `localhost:9093` (external) / `kafka:9092` (in-network)
- Kafka UI:     http://localhost:8085

### 2. Run the app

```bash
mvn spring-boot:run
```

The demo data generator seeds 6 products / 6 users on startup, then emits
one order + (usually) matching payment every 1.5 seconds. Watch logs or
Kafka UI to see records flow through the topologies.

### 3. Hit the API

```bash
# Publish a one-shot order + payment
curl -X POST http://localhost:8080/api/demo/quick-order

# Query the local state store (interactive queries)
curl http://localhost:8080/api/state/products/P-001
curl http://localhost:8080/api/state/users/U-1001
curl http://localhost:8080/api/state/category-revenue?windowMinutes=10
curl http://localhost:8080/api/state/user-stats/U-1001?windowMinutes=30

# Health + metrics
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/prometheus
curl http://localhost:8080/actuator/kafkastreams
```

### Publish a custom order

```bash
curl -X POST http://localhost:8080/api/demo/orders \
  -H 'Content-Type: application/json' \
  -d '{
    "userId":"U-1001",
    "productId":"P-001",
    "quantity":3,
    "unitPrice":150000
  }'
```

## Run the tests

```bash
mvn test
```

All topology tests run with `TopologyTestDriver` — no broker, no Docker, fast.

## Topics

| Topic | Cleanup policy | Purpose |
|-------|----------------|---------|
| `orders.v1` | delete | Order events (input) |
| `payments.v1` | delete | Payment events (input) |
| `products.v1` | **compact** | Product catalog (KTable) |
| `users.v1` | **compact** | User catalog (KTable) |
| `enriched-orders.v1` | delete | Orders + product + user metadata |
| `completed-orders.v1` | delete | Order ⨝ Payment within 10-minute window |
| `category-revenue.v1` | delete | Per-category revenue, 1-minute tumbling |
| `user-order-counts.v1` | delete | Per-user rolling 5-min counts (1-min step) |
| `user-sessions.v1` | delete | Per-user shopping sessions (30s inactivity gap) |
| `streams.dlq.v1` | delete (7d) | Poison-pill records with origin headers |

## Configuration

Override anything in `application.yml` via env vars or `--app.*=…` args:

| Variable | Default | Purpose |
|----------|---------|---------|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9093` | Broker endpoint |
| `KAFKA_STATE_DIR` | `./data/kafka-streams` | RocksDB state directory |
| `DEMO_AUTO_GENERATE` | `true` | Toggle the scheduled producer |
| `app.kafka.num-stream-threads` | `2` | Stream threads per JVM |
| `app.kafka.processing-guarantee` | `at_least_once` | Set to `exactly_once_v2` for EOS |
| `app.kafka.replication-factor` | `1` | Bump for multi-broker prod |

## Build a Docker image

```bash
docker build -t kafka-streams-poc:1.0.0 .
docker run --rm -p 8080:8080 \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9093 \
  kafka-streams-poc:1.0.0
```

## Layout

```
src/main/java/com/vndirect/kstreams/
├── KafkaStreamsPocApplication.java
├── config/          AppProperties, KafkaStreamsConfig, SerdesConfig, TopicConfig, JacksonConfig
├── error/           DlqDeserializationExceptionHandler, LoggingProductionExceptionHandler
├── health/          KafkaStreamsHealthIndicator
├── model/           OrderEvent, PaymentEvent, Product, User, EnrichedOrder, CompletedOrder, ...
├── producer/        EventPublisher, DemoDataGenerator
├── serdes/          JsonSerde (generic, DLQ-friendly)
├── topology/        StreamsTopologyBuilder + 3 topologies
└── api/             DemoController, StateStoreController
```

## Design notes

- The three topologies share a single `StreamsBuilder` so they all run in the
  same `application-id` and can be wired together — the enrichment topology
  *returns* a `KStream<String, EnrichedOrder>` that the aggregations consume
  directly, avoiding a re-read from the enriched-orders topic.
- Reference data uses `GlobalKTable` so the orders stream doesn't need to be
  co-partitioned by `productId`/`userId`. Trade-off: each instance holds a
  full copy in RAM.
- Window grace periods absorb out-of-order events without holding state forever.
- Records that fail deserialization land in the DLQ with full origin
  metadata — replay or alert from there.
- The Spring Kafka factory injects the shared `StreamsBuilder` into the
  `@Autowired` method of `StreamsTopologyBuilder`, which is how Spring's
  `@EnableKafkaStreams` is designed to be extended.

## Scaling

The 1-pod happy path is done. Before running more than one instance, walk the
checklist at the bottom of [CONSISTENCY.md](CONSISTENCY.md#6-checklist-what-to-change-before-running-1-pod)
— specifically: raise partition counts, add `application.server`, make the
state directory persistent, enable standby replicas, and implement Interactive
Queries routing. All are one- to five-line changes but every one is
load-bearing at N > 1.

## Interactive explainer

Open [demo.html](demo.html) directly in a browser. It renders the three
topologies, lets you step through the enrichment / windowing / join flows, and
(when the Spring app is running on `localhost:8080`) can post a demo order and
poll the state-store API live.
