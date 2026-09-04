# TECHNICAL.md — How each POC solves the distributed-transaction problem

> Read [ISSUE.md](ISSUE.md) first for *what* we are solving and the invariants we protect.
> This document is *how*: the hard problem in each flavor, the solution shape, the key tech
> mapped to the responsibility it carries, how each sub-problem is closed, and the tech debt
> we knowingly leave open.

---

## 0. The shared problem, restated

Turn one business intent ("place an order") into four writes across four independent
databases, and guarantee the whole thing either **commits everywhere** or **compensates
everywhere** — under crashes, restarts, duplicate delivery, out-of-order messages, and
concurrent sagas. No shared transaction exists, so we simulate atomicity with a **sequence
of local transactions plus explicit compensations** (the saga pattern).

Two implementations, identical contracts (`common/` sealed event records + enums):

| | Choreography | Orchestration |
|---|---|---|
| Who decides the next step | Each service, reacting to events | One Temporal workflow |
| Transport | Kafka topic `saga.events`, keyed by `sagaId` | Temporal gRPC task queue |
| Where saga state lives | Scattered: each service's DB + Kafka offsets | Centralized: Temporal event history |
| Compensation knowledge | Implicit in each listener's wiring | Explicit `saga.addCompensation(...)` lines |
| "Where is order X?" | Query 4 DBs / read the topic / trace logs | Open Temporal UI, search `orderId` |

---

## 1. Choreography flavor

### The hard problem
There is **no coordinator**. Correctness has to *emerge* from services that each know only
their own step and the events they react to. That buys maximum decoupling and throughput,
but every hard sub-problem (ordering, duplicates, resume-after-crash, compensation
ordering) must be solved **locally, by every participant**, with no global view to lean on.

### Solution shape
A single Kafka topic `saga.events` is the spine. Each event carries a `sagaId` and a unique
`eventId`. The forward chain is `OrderCreated → PaymentCompleted → InventoryReserved →
ShippingScheduled`; on failure a service emits a failure event and the chain unwinds
"back up the stack": `ShippingFailed → InventoryReleased → PaymentRefunded → OrderCancelled`.
`order-service` is the **only terminal authority** — it watches the chain and stamps the
final `COMPLETED` / `CANCELLED` state.

### Key tech by responsibility

| Responsibility | Tech / mechanism | Where |
|---|---|---|
| Event transport | Kafka (KRaft, single topic `saga.events`) | `docker-compose.yml`, `common/KafkaTopics.java` |
| In-order delivery per saga | Kafka **partition key = `sagaId`** | [`SagaEventPublisher`](choreography/order-service/src/main/java/com/example/saga/choreography/order/messaging/SagaEventPublisher.java:26) |
| Duplicate suppression | `processed_events(event_id)` table, checked first in every handler | [`OrderSagaCoordinator.handle`](choreography/order-service/src/main/java/com/example/saga/choreography/order/service/OrderSagaCoordinator.java:50), [`InventoryService.handle`](choreography/inventory-service/src/main/java/com/example/saga/choreography/inventory/service/InventoryService.java:59) |
| Retry + poison-message isolation | Spring Kafka `DefaultErrorHandler` + `ExponentialBackOff` → `saga.events.DLT` | [`KafkaConfig`](choreography/order-service/src/main/java/com/example/saga/choreography/order/config/KafkaConfig.java:24) |
| Manual offset commit (no loss on crash) | `Acknowledgment.acknowledge()` only after handler succeeds | [`SagaEventListener`](choreography/order-service/src/main/java/com/example/saga/choreography/order/messaging/SagaEventListener.java:20) |
| Missing-context race | `inventory`/`shipping` persist a `saga_context` row from `OrderCreated`; if the trigger event arrives first, throw → retry replays it later | [`InventoryService.reserve`](choreography/inventory-service/src/main/java/com/example/saga/choreography/inventory/service/InventoryService.java:92) |
| Concurrent stock races | Pessimistic `SELECT … FOR UPDATE` (`findForUpdate`) + `@Version` | `InventoryService.reserve`/`release`, `Stock` entity |
| State machine / terminal authority | Explicit `switch` on sealed `SagaEvent` in the order coordinator | `OrderSagaCoordinator` |
| Per-saga log correlation | `MDC.put("sagaId", …)` around every handler | all `*Service`/`*Coordinator` |

### How each sub-problem is closed
- **Partial failure → compensation.** A downstream failure event (e.g. `ShippingFailed`)
  is consumed by the *upstream* service, which reverses its own step and emits the next
  compensation event. Each service only ever needs to know its own counter-operation.
- **Crash mid-flow.** Offsets are committed **manually and only after** the DB transaction
  succeeds. A crash before `ack` means Kafka redelivers; the idempotency check makes the
  replay a no-op if the write already landed.
- **Duplicate delivery.** First line of every handler: `if processed_events contains
  eventId → return`. At-least-once transport, exactly-once effect.
- **Ordering.** Keying by `sagaId` pins all of one saga's events to one partition, so a
  consumer sees them in emit order. Cross-saga parallelism is preserved.
- **Compensation ordering.** Emerges naturally from the reverse event chain; the order
  service is the single place that decides the saga is truly finished.

---

## 2. Orchestration flavor

### The hard problem
The forward logic is easy to *write* — but the **durability** is hard. "Charge, then
reserve, then ship, and if anything throws, undo in reverse" must survive the worker
process dying **at any instruction boundary**, must not re-run side effects it already ran,
and must retry both forward steps and compensations with different policies — all without
the developer hand-rolling a state machine, checkpoint table, or resume logic.

### Solution shape
A **Temporal workflow** (`OrderSagaWorkflowImpl`) is the durable brain. Temporal records
every step in an append-only **event history**; if a worker crashes, another worker replays
the history and continues from exactly where it left off. The workflow reads like
straight-line code, and Temporal's `Saga` primitive collects compensations to run in
reverse on failure.

### Key tech by responsibility

| Responsibility | Tech / mechanism | Where |
|---|---|---|
| Durable execution / resume-after-crash | Temporal **workflow event history** (replay) | Temporal server, `OrderSagaWorkflowImpl` |
| Straight-line saga code | `io.temporal.workflow.Saga` + `addCompensation(...)` | [`OrderSagaWorkflowImpl.placeOrder`](orchestration/orchestrator-service/src/main/java/com/example/saga/orchestration/workflow/OrderSagaWorkflowImpl.java:55) |
| Compensate in reverse, keep going on error | `Saga.Options` `parallelCompensation=false`, `continueWithError=true` | [`OrderSagaWorkflowImpl:58`](orchestration/orchestrator-service/src/main/java/com/example/saga/orchestration/workflow/OrderSagaWorkflowImpl.java:58) |
| Transient-failure retries | `RetryOptions` (backoff ×2, cap 30s, max 5 attempts) per activity | [`OrderSagaWorkflowImpl:38`](orchestration/orchestrator-service/src/main/java/com/example/saga/orchestration/workflow/OrderSagaWorkflowImpl.java:38) |
| Business failures = stop, don't retry | `NonRetryable*Exception` listed in `doNotRetry` | [`OrderSagaWorkflowImpl:43`](orchestration/orchestrator-service/src/main/java/com/example/saga/orchestration/workflow/OrderSagaWorkflowImpl.java:43), `exception/` package |
| Exactly-once effect under at-least-once activity dispatch | Activity idempotency keyed on `orderId` / `paymentId` | [`PaymentActivitiesImpl.charge`](orchestration/orchestrator-service/src/main/java/com/example/saga/orchestration/activity/PaymentActivitiesImpl.java) |
| Concurrent stock races | Pessimistic lock inside the reserve activity + `@Version` | `InventoryActivitiesImpl`, `Stock` entity |
| Visibility / "where is order X?" | Temporal UI (full history per `orderId`) | http://localhost:8233 |
| Local read model | Orchestrator persists its own `Order` row, updated from the workflow result | `orchestrator-service` |

### How each sub-problem is closed
- **Partial failure → compensation.** Each forward step registers its undo the moment it
  succeeds (`saga.addCompensation(() -> payment.refund(id))`). One `catch (ActivityFailure)`
  → `saga.compensate()` runs them all in reverse.
- **Crash mid-flow.** Nothing to hand-roll: the workflow's history is the checkpoint.
  A new worker replays deterministic workflow code and resumes at the next un-run activity.
- **Duplicate delivery.** Temporal dispatches activities **at-least-once**, so each activity
  is written to be idempotent — a repeat `charge(orderId)` returns the stored `paymentId`
  instead of charging again.
- **Retry policy split.** Infra blips retry automatically; "insufficient funds / out of
  stock / invalid address" throw non-retryable exceptions so the saga fails fast and
  compensates instead of hammering a doomed call five times.
- **Compensation durability.** Compensations are themselves activities, so Temporal retries
  them too — undo is as durable as the forward path.

---

## 3. Side-by-side: which pressure each flavor takes

| Sub-problem | Choreography answer | Orchestration answer |
|---|---|---|
| All-or-nothing | Reverse event chain, order-service is terminal authority | `Saga.compensate()` in reverse |
| Resume after crash | Kafka redelivers uncommitted offset + idempotent replay | Workflow history replay |
| Duplicate suppression | `processed_events` table per consumer | Idempotent activities keyed on business id |
| Ordering | Partition by `sagaId` | Workflow code is single-threaded by design |
| Concurrency on stock | `FOR UPDATE` + `@Version` | Same, inside the activity |
| Transient vs business failure | Retry+DLT vs. explicit failure event | `RetryOptions` vs. `doNotRetry` |
| Observability | MDC `sagaId` in logs, Kafka UI, DLT | Temporal UI, one place |
| Add a 5th step | New service + update every "done" producer | One activity call + one line in the workflow |

**Rule of thumb:** choreography wins where throughput and autonomy dominate and steps are
few and stable; orchestration wins where the workflow is complex, reorders often, or humans
must audit and intervene.

---

## 4. Tech debt to acknowledge

These are **known and deliberate** for a POC. Each would need closing before production.

1. **No transactional outbox (dual-write hazard) — highest priority.**
   Producers write to the DB and publish to Kafka inside the same `@Transactional` block.
   If the broker is unreachable *between commit and publish*, the DB row exists but the
   event is lost, and the saga stalls with no downstream trigger. This breaks the "forward
   progress" invariant. **Fix:** write the event to an `outbox` table in the *same* local
   transaction, then relay it to Kafka via Debezium CDC or `spring-modulith-events-jpa`.
   (Orchestration is *not* exposed to this — Temporal persists intent before dispatch.)

2. **No end-to-end distributed tracing.** `sagaId` lives in logs (MDC) but there is no
   trace/span propagated across Kafka and Temporal. **Fix:** Micrometer Tracing +
   OpenTelemetry, propagate `sagaId` as a span baggage/attribute.

3. **JSON events, no schema registry.** Contracts are `JsonSerializer` POJOs. Safe while
   producer and consumers share the `common` module; fragile once an external consumer
   appears and someone renames a field. **Fix:** Avro/Protobuf + Confluent Schema Registry
   with compatibility checks in CI.

4. **DLT has no automated drain / replay.** `saga.events.DLT` captures poison messages but
   there is no tooling to inspect, fix, and re-inject them. Today it is a manual forensic
   trail. **Fix:** a DLT consumer with a replay endpoint + alerting on DLT depth.

5. **Simulated side effects.** Payment/inventory/shipping are in-process fakes with magic
   inputs. No real gateway timeouts, idempotency keys from providers, or webhook callbacks.

6. **Temporal on a Postgres dev image.** `temporalio/auto-setup` is single-node and not
   HA. **Fix:** a Cassandra-backed (or managed Temporal Cloud) cluster, multi-region, for
   real durability guarantees.

7. **No saga timeout / stuck-saga sweeper in choreography.** If a middle event is lost
   (see #1) nothing times the saga out. Orchestration gets this free via
   `ScheduleToCloseTimeout`. **Fix:** a scheduled reaper that flags sagas stuck in a
   non-terminal state past an SLA.

8. **Compensation is best-effort, not guaranteed-terminal, in choreography.** If a
   compensation event is lost, the order can sit in `COMPENSATING` forever. Same root cause
   as #1 + #7.

See [CONSISTENCY.md](CONSISTENCY.md) for how these guarantees behave under horizontal
scaling (many pods / VMs).
