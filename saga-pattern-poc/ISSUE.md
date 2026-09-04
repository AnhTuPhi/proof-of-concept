# ISSUE — The distributed-transaction problem this POC exists to solve

## One sentence

Placing an order touches **four independent services, each with its own database**, and
there is **no shared transaction** that can commit or roll back all four together — so we
need a way to either finish every step or undo every step that already succeeded, even
when services crash, restart, or receive the same message twice.

---

## Context

"Place an order" is a single business intent, but it is physically four writes in four
bounded contexts:

| Step | Service | Local write | Side effect on the world |
|------|---------|-------------|--------------------------|
| 1. Record order | `order-service` | `orders` row | — |
| 2. Charge card  | `payment-service` | `payments` row | **money leaves the customer** |
| 3. Reserve stock | `inventory-service` | `stock` + `reservations` rows | **units become unavailable to others** |
| 4. Schedule shipment | `shipping-service` | `shipments` row | **a courier gets dispatched** |

Each service owns its data. There is no shared schema, so a single
`BEGIN … COMMIT` cannot span all four. A classic 2-phase-commit (XA) coordinator *could*
in theory, but it holds locks across the network for the whole flow, blocks every
participant on the slowest one, and stalls completely if the coordinator dies mid-commit.
At order-processing throughput that is a non-starter — which is exactly why this POC
reaches for the **saga pattern** instead.

## The hard requirement: all-or-nothing without a shared transaction

The system must guarantee that an order ends in exactly one of two consistent states:

- **Committed** — payment charged **and** stock reserved **and** shipment scheduled, or
- **Fully compensated** — every step that *did* succeed has been reversed (refund the
  charge, release the stock, cancel the shipment) and the order is marked `CANCELLED`.

What it must **never** do is leave a half-order: money taken but nothing shipped, stock
locked forever behind a payment that failed, a courier dispatched for goods that were
never reserved.

## Why this is genuinely hard

Any of these can happen between step 1 and step 4, and each one threatens the invariant:

1. **Partial failure.** Payment succeeds, then inventory is out of stock. The charge is
   real and must be walked back. There is no `ROLLBACK` that reaches into the payment DB.
2. **Process crash mid-flow.** A pod is killed by Kubernetes (deploy, OOM, node drain)
   right after charging but before reserving. On restart the saga must *know it was mid-
   flight* and resume — not silently drop the order, and not re-charge.
3. **Duplicate delivery.** The transport (Kafka, or Temporal's activity dispatch) is
   **at-least-once**. The same "payment completed" message can arrive twice. Reserving
   stock twice, or charging twice, is a correctness bug that costs real money.
4. **Out-of-order / racing messages.** In choreography, `PaymentCompleted` can reach
   inventory before the `OrderCreated` that carries the product/quantity context.
5. **Concurrent sagas on shared rows.** Two orders for the same SKU race on the same
   `stock` row; without locking, both read "10 available" and both reserve, oversubscribing.
6. **No single place to answer "where is order X?"** State is smeared across four
   databases and a message log. Operations and support need a truthful, current answer.
7. **Compensation can itself fail.** The refund call can time out. Undo has to be as
   durable and retryable as the forward path — you cannot "give up" halfway through undo.

## What we are protecting (the invariants)

- **No money without goods, no goods without money** — payment, reservation and shipment
  are consistent with each other or all reversed.
- **No leaked stock** — every reservation is eventually either shipped or released.
- **Exactly-once *effect*** despite at-least-once *delivery* — retries and duplicates never
  double-charge or double-reserve.
- **Forward progress under failure** — a crashed or restarted participant resumes the saga
  to a terminal state; nothing gets stuck "in progress" forever.

## Scope of the POC

This repo demonstrates **two different answers** to the same problem so they can be compared
directly:

- **Choreography** — no central brain; each service reacts to events on a Kafka topic and
  emits the next (or a compensating) event. See [`choreography/`](choreography/).
- **Orchestration** — a Temporal workflow is the central brain; it calls activities in
  order and runs registered compensations in reverse on failure. See
  [`orchestration/`](orchestration/).

The contracts (events, DTOs, enums) are **identical** — shared in [`common/`](common/) — so
only the coordination mechanism differs.

## Out of scope (deliberately)

- Real payment gateways, real carriers, real stock feeds — all side effects are simulated,
  with failures triggered by magic inputs (`deadbeat*` customer, `OUT_OF_STOCK*` product,
  `INVALID` address).
- Authn/authz, multi-tenancy, rate limiting.
- The **transactional outbox** (DB write + publish are not yet atomic) — this is a known,
  documented gap, see [TECHNICAL.md](TECHNICAL.md#tech-debt-to-acknowledge).

---

See **[TECHNICAL.md](TECHNICAL.md)** for how each flavor solves each sub-problem, and
**[CONSISTENCY.md](CONSISTENCY.md)** for what happens to these guarantees when you scale to
many pods / VMs.
