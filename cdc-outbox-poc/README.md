# cdc-outbox-poc

Production-ready Proof of Concept for **Change Data Capture (CDC)** using **Debezium + Kafka**, combined with the **Transactional Outbox** pattern.

Solves the classic **dual-write problem**: how do you reliably update a database *and* publish a message to a broker in one atomic action? You can't — networks fail, processes crash, and two-phase commit is too expensive. The outbox pattern sidesteps this by writing both the business state and the event to the **same database transaction**, then letting CDC stream the event out asynchronously.

Used in production by Uber, Shopify, Wix, and many others.

> **Read the "why" first.** The four docs below explain the hard problems this POC exists
> to solve, how each hop of the pipeline solves them, and how the design scales. This
> README is the "how to run it" cheat sheet.
>
> - **[ISSUE.md](ISSUE.md)** — the hard problem on one page: dual-write, at-least-once
>   delivery, per-aggregate ordering, outbox growth, poison messages, WAL bloat. The
>   explicit non-goals live here too.
> - **[TECHNICAL.md](TECHNICAL.md)** — per-component (producer, Debezium/SMT, consumer):
>   what makes it hard, what invariant we're protecting, solution shape, key tech by
>   responsibility, tech debt to acknowledge.
> - **[CONSISTENCY.md](CONSISTENCY.md)** — what changes (and what doesn't) when you
>   scale from one pod to a K8s fleet or a VM cluster. Producer HPA, Debezium HA,
>   consumer partition math, WAL/slot management.
> - **[docs/flow.html](docs/flow.html)** — a standalone HTML explainer of the flow and
>   the key tech. Open it in a browser, no server needed.

---

## Architecture

```
                                     ┌────────────────────────────────────────────┐
                                     │              order-service                 │
                                     │  Spring Boot 3.4 · Java 21 · PostgreSQL    │
                                     │                                            │
   HTTP POST /orders ────────────────▶  ┌───────────────────────────────────────┐  │
                                     │  │  @Transactional                       │  │
                                     │  │    1. INSERT INTO orders ...          │  │
                                     │  │    2. INSERT INTO outbox_events ...   │  │
                                     │  └────────────┬──────────────────────────┘  │
                                     │               │                             │
                                     │               ▼                             │
                                     │      ┌─────────────────┐                    │
                                     │      │   PostgreSQL    │                    │
                                     │      │  (wal_level =   │                    │
                                     │      │    logical)     │                    │
                                     │      └────────┬────────┘                    │
                                     └───────────────┼─────────────────────────────┘
                                                     │ WAL stream
                                                     ▼
                                             ┌───────────────┐
                                             │   Debezium    │
                                             │   Connect     │
                                             │  (Outbox SMT) │
                                             └───────┬───────┘
                                                     │
                                                     ▼
                                            ┌────────────────┐
                                            │     Kafka      │
                                            │  topic: orders │
                                            └────────┬───────┘
                                                     │
                                                     ▼
                       ┌─────────────────────────────────────────────────────────┐
                       │              notification-service                       │
                       │  Spring Boot 3.4 · Java 21 · Kafka consumer             │
                       │                                                         │
                       │  · Idempotent (dedup by event_id)                       │
                       │  · Retries with exponential backoff                     │
                       │  · Dead Letter Topic on poison messages                 │
                       └─────────────────────────────────────────────────────────┘
```

---

## Why this design?

| Problem                                      | Solution                                            |
|----------------------------------------------|-----------------------------------------------------|
| DB write succeeds, Kafka publish fails       | Outbox row written in same TX — guaranteed durable  |
| Kafka publish succeeds, DB rollback          | Impossible — TX rollback removes the outbox row     |
| Consumer crashes mid-processing              | Kafka offset commit after processing; idempotent    |
| Duplicate events (at-least-once delivery)    | Consumer dedups by `event_id` (processed_events)    |
| Outbox table grows unbounded                 | Debezium reads WAL, not the table — table can be   |
|                                              | truncated by a scheduled cleanup job                |
| Polling the outbox adds DB load              | Debezium consumes the WAL — zero polling overhead   |

---

## Tech stack

- **Java 21** (records, pattern matching, virtual threads ready)
- **Spring Boot 3.4.3** (matches the DAccount baseline)
- **PostgreSQL 16** with logical replication (`pgoutput` plugin)
- **Debezium 2.7** (`io.debezium.transforms.outbox.EventRouter`)
- **Apache Kafka 3.8** in KRaft mode (no ZooKeeper)
- **Flyway** for schema migrations
- **Testcontainers** for integration tests
- **Micrometer + Actuator** for observability
- **springdoc-openapi** for API docs

---

## Quick start

Prerequisites: Docker Desktop, JDK 21, Maven 3.9+.

```bash
# 1. Start the full stack (Postgres, Kafka, Debezium, both services, Kafka UI)
docker compose up -d --build

# 2. Wait ~30s for services to be healthy, then register the Debezium connector
./scripts/register-connector.sh           # bash
./scripts/register-connector.ps1          # PowerShell

# 3. Create an order
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"cust-001","productSku":"SKU-42","quantity":2,"unitPrice":19.99}'

# 4. Watch the consumer process it
docker compose logs -f notification-service

# 5. Explore in Kafka UI
open http://localhost:8090
```

API docs: <http://localhost:8080/swagger-ui.html>

---

## Project layout

```
cdc-outbox-poc/
├── docker-compose.yml             # Postgres, Kafka, Debezium, both services, Kafka UI
├── pom.xml                        # parent POM
├── order-service/                 # Producer — writes orders + outbox in one TX
├── notification-service/          # Consumer — reads orders topic from Kafka
├── debezium-config/
│   └── outbox-connector.json      # Debezium Outbox Event Router configuration
├── scripts/                       # Convenience scripts (register connector, smoke test)
└── docs/
    ├── ARCHITECTURE.md            # Deeper dive on the patterns
    └── OPERATIONS.md              # Runbook: failure modes, recovery, tuning
```

---

## Verifying it works

Run `./scripts/test-create-order.sh` (or the PowerShell variant) to issue a request, then watch:

1. `docker compose logs order-service` — shows the HTTP request and DB writes
2. The order row in `orders` table + a row in `outbox_events`
3. `docker compose logs debezium` — shows the WAL event being captured
4. `docker compose logs notification-service` — shows the event being consumed
5. `docker compose exec postgres psql -U cdc -d cdc -c "SELECT * FROM outbox_events;"` — confirms outbox grows

The outbox table is intentionally NOT cleaned by Debezium. A scheduled job in `order-service` deletes rows older than 7 days; see [OPERATIONS.md](docs/OPERATIONS.md).

---

## Failure scenarios this PoC handles

| Scenario                                | Outcome                                                            |
|-----------------------------------------|--------------------------------------------------------------------|
| Kafka down when order is created        | Order + outbox row still committed. Debezium catches up on restart |
| Debezium down                           | Same — WAL retains entries until the replication slot advances     |
| Consumer crashes mid-batch              | Re-reads from last committed offset. Idempotent (sees event_id)    |
| Poison message (deserialize failure)    | Routed to `orders.DLT` after retries                               |
| Producer crashes after DB write         | The TX is atomic — either both rows exist or neither does          |

---

## Pitfalls deliberately avoided

- **No polling the outbox table** — Debezium consumes the WAL directly.
- **No 2PC / XA transactions** — those have well-known coordinator failure modes.
- **No "publish-then-write"** — order matters: the DB is the source of truth.
- **No silent error swallowing** in the consumer — failed messages reach the DLT.
- **No raw `find -name`** during DB ops — schema is migrated with Flyway only.

---

## License

MIT.
