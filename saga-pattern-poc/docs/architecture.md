# Architecture

## Business scenario

Place an order across four bounded contexts:

1. **Order** — persist the order and decide the saga is finished.
2. **Payment** — charge the customer; refund on compensation.
3. **Inventory** — reserve stock; release on compensation.
4. **Shipping** — schedule the shipment; cancel on compensation.

Any step can fail. The saga must either complete every step or undo every successful step in reverse — never leave the system half-done.

## Choreography flavor (Kafka)

```
                       saga.events (Kafka, partitioned by sagaId)
                                   │
   ┌───────────────────────────────┼───────────────────────────────┐
   │                               │                               │
   ▼                               ▼                               ▼
order-service          payment-service          inventory-service        shipping-service
 (8081)                  (8082)                     (8083)                  (8084)
   │                       │                          │                       │
   │  POST /orders         │  charges/refunds         │  reserves/releases     │  schedules/cancels
   ▼                       ▼                          ▼                       ▼
order_svc.orders        payment_svc.payments    inventory_svc.stock        shipping_svc.shipments
                                                inventory_svc.reservations
                                                inventory_svc.saga_context shipping_svc.saga_context
```

### Event flow (success path)

```
order-service           payment-service         inventory-service       shipping-service
       │                       │                       │                       │
   POST /orders                │                       │                       │
       │                       │                       │                       │
       │── OrderCreated ─────▶ │ (rememberContext) ──▶ (rememberContext) ───▶ (rememberContext)
       │                       │                       │                       │
       │              chargePayment                    │                       │
       │ ◀── PaymentCompleted ─                        │                       │
       │                                       reserveStock                    │
       │ ◀──────────────────────  InventoryReserved ───                        │
       │                                                           scheduleShipping
       │ ◀──────────────────────────────────────────────  ShippingScheduled ───
       │ (mark COMPLETED)
```

### Event flow (compensation path — shipping fails)

```
order-service           payment-service         inventory-service       shipping-service
       │                       │                       │                       │
       │                       │                       │       ShippingFailed
       │ ◀─────────────────────│───────────────────────│──────────────────
       │  (mark COMPENSATING)  │                       │
       │                                       (release stock)
       │ ◀────────────────────────────── InventoryReleased
       │                                                                       
       │                  (refund payment)
       │ ◀────── PaymentRefunded
       │ (mark COMPENSATED/CANCELLED)
```

The order service is the **only** terminal authority — it observes the compensation chain and stamps the final outcome.

### Idempotency

Every consumer tracks `processed_events(event_id)` and short-circuits duplicates. Failures inside the listener trigger Spring Kafka's `DefaultErrorHandler`, which retries with exponential backoff and finally publishes to `saga.events.DLT`.

### Saga context

`inventory-service` and `shipping-service` listen for `OrderCreated` purely to save a local `saga_context` row. When their *trigger* event (`PaymentCompleted` / `InventoryReserved`) arrives, they look the context up by `sagaId` instead of calling back to `order-service`. This keeps the choreography decoupled.

---

## Orchestration flavor (Temporal)

```
                  REST POST /orders
                       │
                       ▼
         orchestrator-service (8090)
                       │ persists local Order row
                       │ submits Temporal workflow
                       ▼
              Temporal server (7233)
                       │ durably stores history
                       │ schedules workflow tasks
                       ▼
         OrderSagaWorkflowImpl
            │   Saga primitive
            │
            ├──▶ PaymentActivitiesImpl.charge          ── saga.addCompensation(refund)
            ├──▶ InventoryActivitiesImpl.reserve       ── saga.addCompensation(release)
            └──▶ ShippingActivitiesImpl.schedule       ── saga.addCompensation(cancel)
                       │
                       │  on any ActivityFailure → saga.compensate()
                       ▼
            workflow returns OrderSagaResult
                       │
                       ▼
         orchestrator-service updates local Order row
```

### How Temporal handles failure

- Each activity has a `RetryOptions` policy. Transient exceptions (DB timeouts, network blips) are retried up to 5 times with exponential backoff.
- "Business" failures (insufficient funds, out of stock, invalid address) throw a `NonRetryable*Exception`. These are listed in the workflow's `doNotRetry` set, so Temporal aborts the activity immediately.
- When an activity finally fails, the workflow catches `ActivityFailure` and calls `Saga.compensate()`, which runs the registered compensations in reverse order. Each compensation is itself retried by Temporal if it fails.

### Where state lives

| State | Choreography | Orchestration |
|---|---|---|
| Saga step progress | Each service's own DB row + Kafka offsets | Temporal event history (durable, queryable) |
| Compensation knowledge | Implicit in each service's listener wiring | Explicit `saga.addCompensation(...)` lines |
| Visibility | Tail `saga.events` topic, or query each service | Temporal UI shows the full workflow history |
| Recovery on crash | Kafka rewinds offsets; idempotent listeners replay | Temporal re-schedules the workflow task |

---

## Trade-off summary

| Aspect | Choreography | Orchestration |
|---|---|---|
| Coupling | Low — services only know event types | Moderate — orchestrator knows the order of steps |
| Visibility | Hard — state is scattered | Easy — one place to inspect (Temporal UI) |
| Adding a new step | Add a listener and update producers | Add an activity call in the workflow |
| Adding cross-cutting policy (timeout, retry) | Per-service plumbing | One workflow-level config |
| Operational dependency | Just Kafka | Kafka not required; Temporal server is the new dependency |
| Best for | Few well-bounded steps, high autonomy | Many steps, frequent reordering, audit requirements |

Both patterns are used in production by Uber, Airbnb, and Netflix — usually side by side: choreography for high-throughput pipelines, orchestration for complex business workflows where humans need to ask "where is order X right now?"
