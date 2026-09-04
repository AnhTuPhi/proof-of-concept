# Saga Pattern POC — Choreography vs. Orchestration

Two implementations of the same distributed transaction (place an order across
payment, inventory and shipping bounded contexts), side by side:

| Flavor | Coordinator | Transport | Failure handling |
|---|---|---|---|
| **Choreography** | None — each service reacts to events | Kafka topic `saga.events` | Each service emits a compensating event on its way back |
| **Orchestration** | Temporal workflow | Temporal gRPC | `Saga.compensate()` calls registered compensation activities in reverse |

Built on Spring Boot 3.4.3 / Java 21. Both flavors share a `common` module of
sealed event records and domain enums, so the contracts are identical — only
the wiring differs.

See [docs/architecture.md](docs/architecture.md) for sequence diagrams and the
trade-off table.

---

## Documentation map

Start here to understand *why* this POC exists and *how* it holds together:

| Doc | Answers |
|---|---|
| **[ISSUE.md](ISSUE.md)** | What distributed-transaction problem are we solving, and what invariants must never break? |
| **[TECHNICAL.md](TECHNICAL.md)** | The hard problem in each flavor, the solution shape, key tech mapped to responsibility, how each sub-problem is closed, and the tech debt we knowingly leave open. |
| **[CONSISTENCY.md](CONSISTENCY.md)** | Do the guarantees survive when you `kubectl scale` to many pods / VMs? What to configure and what to fix first. |
| **[docs/architecture.md](docs/architecture.md)** | Sequence diagrams and the full trade-off table. |
| **[docs/saga-explorer.html](docs/saga-explorer.html)** | 🖱️ **Interactive explorer** — step through the happy path and all three compensation paths for both flavors. Open it in a browser. |

The interactive explorer is the fastest way to *see* the forward and compensation
(undo) flows. It needs no server — just open the file:

```bash
# macOS
open docs/saga-explorer.html
# Windows
start docs/saga-explorer.html
# Linux
xdg-open docs/saga-explorer.html
```

---

## The problem in one paragraph

"Place an order" is one business intent but four writes across four services, each with
its own database and **no shared transaction**. The saga pattern replaces the missing
`COMMIT`/`ROLLBACK` with a **sequence of local transactions plus explicit compensations**:
finish every step, or undo every step that already succeeded — correctly, under crashes,
duplicate delivery, and concurrent orders. This repo shows the two canonical ways to
coordinate that: **choreography** (services react to events) and **orchestration** (a
Temporal workflow drives the steps). Full detail in [ISSUE.md](ISSUE.md) and
[TECHNICAL.md](TECHNICAL.md).

---

## Layout

```
saga-pattern-poc/
├── common/                     # shared events, DTOs, enums (used by both flavors)
├── choreography/
│   ├── order-service/          # port 8081 — REST + saga initiator + tracker
│   ├── payment-service/        # port 8082 — charges / refunds
│   ├── inventory-service/      # port 8083 — reserves / releases stock
│   └── shipping-service/       # port 8084 — schedules / cancels shipments
├── orchestration/
│   └── orchestrator-service/   # port 8090 — REST + Temporal workflow + activities
├── infra/                      # postgres bootstrap SQL
├── scripts/                    # demo + run scripts
└── docker-compose.yml          # Kafka, Temporal, Postgres, Kafka UI, Temporal UI
```

---

## Prerequisites

- JDK 21
- Maven 3.9+
- Docker + Docker Compose

---

## Quickstart

### 1. Boot the infrastructure

```bash
docker compose up -d
```

This launches:

| Component | URL | Purpose |
|---|---|---|
| Postgres | `localhost:5432` | `saga`, `temporal`, `temporal_visibility` databases |
| Kafka | `localhost:9094` (external listener) | Choreography event bus |
| Kafka UI | http://localhost:8088 | Browse topics, consumer groups |
| Temporal | `localhost:7233` (gRPC) | Workflow service |
| Temporal UI | http://localhost:8233 | Workflow history visualisation |

Wait ~30 seconds for `temporal` to finish auto-setting up its schema.

### 2. Build everything

```bash
mvn -DskipTests clean install
```

### 3. Run the choreography flavor

In one shell:

```bash
./scripts/run-choreography.sh
```

In another:

```bash
./scripts/demo-choreography.sh
```

You'll see four scenarios fired in sequence: happy path, payment fail,
inventory fail, shipping fail. The last three exercise the compensation
chain. Tail individual service logs in `logs/*.log` or open
http://localhost:8088 to watch events arrive on `saga.events`.

### 4. Run the orchestration flavor

In one shell:

```bash
./scripts/run-orchestration.sh
```

In another:

```bash
./scripts/demo-orchestration.sh
```

Open http://localhost:8233 to inspect each workflow's full history —
including compensation activities executed in reverse order on failure.

---

## REST API (identical surface for both flavors)

```bash
# happy path
curl -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' \
  -d '{
    "customerId": "cust-001",
    "productId":  "SKU-1",
    "quantity":   2,
    "unitPrice":  19.99,
    "shippingAddress": "123 Main St"
  }'

# orchestration flavor uses the same shape, just port 8090
curl -X POST http://localhost:8090/orders ...
```

### Forcing each failure scenario

| To trigger | Set field to |
|---|---|
| Payment failure | `customerId` starts with `deadbeat` |
| Inventory failure | `productId` starts with `OUT_OF_STOCK` |
| Shipping failure | `shippingAddress` contains `INVALID` |

### Inspect

```bash
curl http://localhost:8081/orders/<orderId>
curl http://localhost:8090/orders/<orderId>
```

Swagger UIs:
- Choreography order-service: http://localhost:8081/swagger-ui.html
- Orchestrator: http://localhost:8090/swagger-ui.html

---

## Production-readiness notes

Both flavors already include the patterns you'd ship to production:

| Concern | Choreography | Orchestration |
|---|---|---|
| Idempotency | `processed_events` table per consumer | Activity-level idempotency on `orderId` |
| Retries | Spring Kafka `DefaultErrorHandler` + exponential backoff | Temporal `RetryOptions` per activity |
| Dead-letter | `saga.events.DLT` | Failed workflows visible in Temporal UI |
| Ordering | Kafka partitioning by `sagaId` | Workflow code is single-threaded by design |
| Optimistic / pessimistic locking | `@Version` + `PESSIMISTIC_WRITE` on stock | Same, in activities |
| Graceful shutdown | `spring.lifecycle.timeout-per-shutdown-phase=30s` | Temporal SDK drains in-flight tasks |
| Schema migration | Flyway per service | Flyway in orchestrator |
| Observability | Actuator `/health`, `/prometheus`, MDC `sagaId` in logs | Same + Temporal UI |
| Non-retryable failures | Business compensation events (`PaymentFailed`, ...) | `NonRetryable*Exception` classes listed in `doNotRetry` |

### Known gaps worth closing for a real deployment

- **Transactional outbox** on the producer side — current code writes to the DB
  then publishes to Kafka in the same `@Transactional` block. If the broker is
  down between commit and publish, the event is lost. Drop in a Debezium outbox
  or `spring-modulith-events-jpa`. See [cdc-outbox-poc](../cdc-outbox-poc/) in
  this workspace.
- **End-to-end tracing** — wire Micrometer Tracing + OpenTelemetry so the
  `sagaId` propagates as a span attribute across Kafka and Temporal.
- **Schema registry** — replace `JsonSerializer` with Avro + Schema Registry
  once the event contract has external consumers.
- **Multi-region Temporal** — run a Cassandra-backed cluster instead of the
  Postgres-backed dev image.

---

## Comparing the two side by side

| You want to ... | Choreography | Orchestration |
|---|---|---|
| Add a fifth step (notifications) | Add a new service + listeners; update every upstream service that sends a "done" event | Add one line in `OrderSagaWorkflowImpl` and one activity |
| Pause a saga in flight | Custom — needs a "paused" state + new event types | `WorkflowStub.signal(...)` + `Workflow.await(...)` |
| Replay a stuck saga | Reset the consumer group offset; ensure idempotency holds | `tctl workflow reset` or Temporal UI reset |
| Run scheduled compensation at midnight | Schedule a CronJob that publishes a custom event | `Workflow.sleep(Duration.ofHours(8))` inside the workflow — durable |
| Tell a stakeholder "where is order X" | Query four DBs / read DLT / trace through logs | Open Temporal UI, search by `orderId` |

Both belong in your toolkit. The companies cited in the brief (Uber, Airbnb,
Netflix) use both — choreography where high throughput dominates, orchestration
where business workflows need to be auditable and editable.

---

## Cleaning up

```bash
docker compose down -v   # drops Postgres volume too
rm -rf logs
```
