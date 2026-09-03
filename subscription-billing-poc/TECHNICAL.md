# TECHNICAL.md — how each POC solves its hard problem

This document goes POC by POC. For each one:

1. **The hard problem** — what is genuinely difficult, not just tedious.
2. **What we are protecting** — the invariants (see [ISSUE.md](ISSUE.md)).
3. **Solution shape** — the design in one paragraph.
4. **Key tech by responsibility** — which class owns which concern.
5. **How each sub-problem is solved.**
6. **Tech debt to acknowledge** — what is faked, cut, or unsafe at scale.

The three POCs share one thing only: the `common` module.

## The shared spine — `common`

| Class | Responsibility |
|-------|----------------|
| [`Money`](common/src/main/java/com/example/billing/common/Money.java) | Immutable currency-aware value type. All arithmetic is `BigDecimal`; every result is rounded to the currency's fraction digits with `HALF_UP`. Cross-currency arithmetic throws. Proration uses **8 digits of internal precision** before the final rounding. |
| [`BillingException`](common/src/main/java/com/example/billing/common/BillingException.java) | Domain-level error that the web layer maps to a 4xx. |

**Why it matters (I1):** money correctness is a *type*, not a convention. No `double`
ever touches an amount, and rounding happens in exactly one place, so the same inputs
always produce the same cents. Everything downstream inherits this.

---

## 1) Proration engine (`proration-engine-poc`, port 8081)

### The hard problem
A customer on `basic` ($10/mo) upgrades to `pro` ($30/mo) 15 days into a 30-day cycle.
What is fair? You owe them credit for the unused half of `basic` and must charge for the
used-forward half of `pro` — **to the exact day**, over a cycle that is really 28–31 days,
not a tidy 30. A downgrade can make the net **negative**. That surplus must **never become
a cash refund** — it becomes credit banked against the next charge.

### What we are protecting
- **I1** — reproducible, auditable amounts (rounding drift here is a support ticket).
- **I4** — money never leaves as cash; downgrades bank credit.

### Solution shape
Split the mechanism into **pure math** and **stateful policy**. The
`ProrationCalculator` is a pure function: `(oldPlan, newPlan, period, changeDate)` →
two line items (a credit for unused old-plan time, a charge for remaining new-plan time),
computed on the *actual* day count. The `SubscriptionService` wraps that in the money
policy: **always emit an invoice, never charge directly**; if the invoice nets positive,
consume existing credit; if it nets negative, bank the surplus as `CustomerCredit`.

### Key tech by responsibility

| Concern | Owner |
|---------|-------|
| Daily proration math (credit + charge line items) | [`ProrationCalculator`](proration-engine-poc/src/main/java/com/example/billing/proration/ProrationCalculator.java) |
| Rounding / precision | `Money.prorate(elapsed, total)` — 8-dp internal, then currency scale |
| No-cash-refund policy, credit consume/bank | `SubscriptionService.applyCreditAndPersist` |
| Two change strategies | `ChangeStrategy` (`IMMEDIATE_PRORATE`, `END_OF_CYCLE`) |
| Concurrency safety on the balance | `CustomerCredit` `@Version` (optimistic lock) |
| Audit trail | `Invoice` + `InvoiceLineItem` (each carries period + planId) |

### How each sub-problem is solved
- **Fair to-the-day math** — cycle length is `DAYS.between(periodStart, periodEnd)`
  (real calendar days). Credit = `oldPrice.prorate(remainingDays, totalDays)`, charge =
  `newPrice.prorate(remainingDays, totalDays)`. Guard clauses reject a `changeDate`
  outside the period.
- **Rounding drift** — all proration goes through `Money.prorate`, which divides at
  8 digits then rounds once. There is no per-line double-rounding.
- **No cash refund (I4)** — `applyCreditAndPersist` inspects the net:
  positive → consume `CustomerCredit` up to the total and add an "Applied credit balance"
  line; negative → `grantCredit` the surplus and add a "Credited to customer balance (no
  cash refund)" line so the invoice still nets to zero and stays auditable.
- **Two strategies** — `IMMEDIATE_PRORATE` issues the two-line invoice now and switches
  the plan today; `END_OF_CYCLE` moves no money and records a zero-amount marker line so
  the change is visible and applied at the next renewal.
- **Cancellation** — `prorateCancellation` credits the unused remainder to the balance;
  the invoice nets to zero, identical in shape to a downgrade.
- **Concurrent balance updates** — `CustomerCredit.@Version` means two simultaneous
  invoices against the same customer cannot silently clobber the balance; the loser gets
  an optimistic-lock failure.

### Tech debt to acknowledge
- **No renewal engine.** `END_OF_CYCLE` records intent but nothing here actually fires at
  period end to apply it — there is no scheduler in this module. In production the
  scheduled plan swap and the next invoice need a batch job (see [CONSISTENCY.md](CONSISTENCY.md)).
- **Credit is a mutable balance, not a ledger.** `CustomerCredit.balance` is a single
  column. Real finance wants an append-only ledger of grants and consumptions (with
  reasons, expiry, and caps) for auditability. Today there is no credit expiry or cap.
- **Monthly cycles only** — `today.plusMonths(1)` is hard-coded; no annual/weekly/anchored
  billing dates, no timezone handling. `today` is a request parameter, not a real clock.
- **`invoicesFor` scans `findAll()`** and filters in memory — fine for a POC, O(n) in prod.
- **Single currency per change** — cross-currency changes are explicitly rejected.

---

## 2) Dunning (`dunning-poc`, port 8082)

### The hard problem
A renewal charge is declined. You cannot cancel immediately (you'd throw away ~40% of
recoverable revenue) and you cannot retry forever (processor fees, customer resentment).
You need a **deterministic retry cadence**, a **lifecycle state machine** with a grace
period and a suspension window, **notifications at each stage**, an **instant recovery**
path when the customer fixes their card, and the whole clock-advancing job must be **safe
to re-run** without double-charging.

### What we are protecting
- **I2** — no double-charge across retries.
- **I3** — no revenue dropped on a recoverable failure.
- **I5** — only legal, idempotent state transitions.

### Solution shape
Model the subscription as an explicit state machine
(`ACTIVE → PAST_DUE → SUSPENDED → CANCELED`, with `→ ACTIVE` on recovery). Persist each
retry as a `RenewalAttempt` row with a `SCHEDULED` status and a due date. A single
`tick(today)` operation is the clock: it runs every attempt due on/before `today`,
promotes `PAST_DUE` subs past their grace period to `SUSPENDED`, and cancels `SUSPENDED`
subs past their window. Charging is behind a `ChargeGateway` seam so tests are
deterministic; notifications go through a `NotificationGateway` so the email cadence is
assertable.

### Key tech by responsibility

| Concern | Owner |
|---------|-------|
| Retry cadence config | [`DunningProperties`](dunning-poc/src/main/java/com/example/billing/dunning/DunningProperties.java) ← `application.yml` (`retry-offsets-days: [3,5,7]`, `grace-period-days`, `suspension-window-days`) |
| State machine + transitions | [`Subscription`](dunning-poc/src/main/java/com/example/billing/dunning/Subscription.java) (`enterPastDue`, `scheduleSuspension`, `suspend`, `cancel`, `reactivate`) |
| Orchestration (attempt / tick / recover) | [`DunningService`](dunning-poc/src/main/java/com/example/billing/dunning/DunningService.java) |
| Payment seam (mockable PSP) | `ChargeGateway` |
| Notification cadence | `NotificationGateway` (in-memory, assertable) |
| Due-work queries | `RenewalAttemptRepository.findDueRetries`, `SubscriptionRepository.findPastDueDueForSuspension` |
| Audit timeline | `DunningEvent` (one row per lifecycle event) |

### How each sub-problem is solved
- **Smart retry schedule** — on failure, `executeAttempt` looks at how many retries were
  used (`attemptNumber - 1`) and schedules the next `RenewalAttempt` at
  `today + retryOffsetsDays[idx]`. Offsets `[3,5,7]` give a Stripe-like cadence.
- **State machine (I5)** — transitions live on the `Subscription` entity, guarded by the
  current status. `attemptInitialRenewal` refuses if not yet due or already canceled.
- **Grace period & suspension window** — on the last failed retry the service records a
  `scheduledSuspensionOn = today + gracePeriodDays`. `tick` flips those to `SUSPENDED`,
  then cancels any `SUSPENDED` sub past `suspendedOn + suspensionWindowDays`.
- **Idempotent tick (I5)** — `tick` only acts on rows still in an actionable state:
  processed attempts flip to `SUCCEEDED`/`FAILED`, transitions are status-guarded. Running
  `tick` twice for the same day changes nothing the second time.
- **Instant recovery (I3)** — `updatePaymentMethod` replaces the card and, if the sub is
  `PAST_DUE`/`SUSPENDED`, calls `settleImmediately` which charges now and, on success,
  reactivates and **cancels all still-pending retries** so no stray retry fires later.
- **Notification cadence** — templates flow `retry_scheduled → final_warning →
  grace_period → service_suspended`, each recorded as an `EMAIL_SENT` `DunningEvent` so
  tests assert the exact sequence.

### Tech debt to acknowledge
- **`tick` is not a real scheduler.** It is triggered by `POST /api/dunning/tick?today=…`.
  Production needs a scheduled job — and, behind multiple pods, **exactly-once execution**
  (leader election / clustered scheduler). See [CONSISTENCY.md](CONSISTENCY.md).
- **No charge idempotency key (I2 risk at scale).** `ChargeGateway.charge` gets no
  idempotency key. On a single instance the attempt rows keep us honest, but two
  concurrent ticks, or a success-at-PSP-but-crash-before-commit, could double-charge.
  `findDueRetries` needs `SELECT … FOR UPDATE SKIP LOCKED` for multi-pod safety.
- **Time is a request parameter** (`today` / `asOf`), for replay convenience — not a
  production clock, and not timezone-aware.
- **Notifications are synchronous and in-memory** — no outbox, no delivery retry. A crash
  between state change and email loses the email (or, with retry added naively, could
  duplicate it). Needs a transactional outbox.
- **Fake gateway** — `cust-bad`'s card always declines; there is no real PSP, no partial
  captures, no 3DS.

---

## 3) Usage-based billing (`usage-billing-poc`, port 8083)

### The hard problem
Meters emit usage events with **at-least-once** delivery: the same event can arrive twice,
events arrive **out of order**, and some arrive **late** (after you thought the day was
closed). You must count each event **exactly once**, aggregate into stable daily totals,
price them through **graduated tiers**, produce an invoice the customer can **reconcile to
±0.0001**, and be able to **prove** the aggregate matches the raw log.

### What we are protecting
- **I1** — the invoice reconciles to the raw event log to the fraction of a cent.
- **I2** — a replayed event is counted once.
- **I3** — a late event is still counted (rollups are rebuildable).

### Solution shape
Make the **database the arbiter of idempotency** (`event_id UNIQUE`), keep **raw events as
the source of truth**, and treat the daily rollup as a **rebuildable materialized
aggregate**. Pricing is a pure tier-walk. A separate reconciliation pass compares
`SUM(raw)` against `SUM(rollup)` per metric so drift is *detected*, not assumed away.

### Key tech by responsibility

| Concern | Owner |
|---------|-------|
| Idempotent ingestion | [`IngestionService`](usage-billing-poc/src/main/java/com/example/billing/usage/IngestionService.java) + `UsageEvent`'s `ux_usage_event_id` UNIQUE constraint |
| Deterministic rollup unit | `UsageEvent.usageDay` = `occurredAt` in **UTC** |
| Rebuildable aggregation | [`RollupService`](usage-billing-poc/src/main/java/com/example/billing/usage/RollupService.java) (delete + rewrite per customer/metric/day) |
| Graduated tier pricing | [`TieredPricingCalculator`](usage-billing-poc/src/main/java/com/example/billing/usage/TieredPricingCalculator.java) |
| Invoice from aggregates, line-item transparency | [`BillingService`](usage-billing-poc/src/main/java/com/example/billing/usage/BillingService.java) |
| Drift detection | [`ReconciliationService`](usage-billing-poc/src/main/java/com/example/billing/usage/ReconciliationService.java) |

### How each sub-problem is solved
- **Idempotent ingestion (I2)** — `IngestionService.ingest` first checks `findByEventId`;
  on a miss it inserts in a `REQUIRES_NEW` transaction. If a concurrent writer wins the
  race, the `DataIntegrityViolationException` on the UNIQUE constraint is caught and the
  existing row returned as a duplicate. **The DB, not app logic, guarantees exactly-once
  storage** — which is exactly what makes this safe across many pods.
- **Out-of-order & late events (I3)** — `RollupService.rollup(customer, day)` deletes the
  existing rollup and recomputes from raw events. Re-running after a late event yields the
  corrected total. Ordering is irrelevant because the aggregate is recomputed, not
  incremented.
- **Deterministic day** — `usageDay` is derived once from `occurredAt` in UTC, so the same
  event always lands in the same rollup bucket regardless of who ingests it.
- **Graduated tiers** — `TieredPricingCalculator` walks tiers in order, filling each
  tier's `width` from the remaining quantity, at 4-dp precision. The final tier may be
  unbounded (`∞`). One `TierCharge` per contributing tier.
- **Line-item transparency (I1)** — each `TierCharge` becomes an `InvoiceLineItem` carrying
  its `quantity` and `pricePerUnit`, so the customer can re-derive the amount and match to
  ±0.0001.
- **Reconciliation (I1)** — `ReconciliationService.reconcile` compares `SUM(raw events)`
  vs `SUM(rollup totals)` and raw count vs rollup count per metric, and flags any
  `quantityDelta`/`eventDelta`. This is the *proof* that the aggregate is honest.

### Tech debt to acknowledge
- **Rollup delete+insert is not atomic against concurrent ingestion.** A late event that
  lands between the delete and the recompute can be missed until the next rollup — which is
  precisely why `ReconciliationService` exists as the safety net. A production version
  uses an upsert / merge or a per-`(customer, day)` lock.
- **Rollup is triggered manually** per customer/day via API. Real systems run a scheduled
  or streaming rollup; and at multi-pod scale the trigger needs the same exactly-once
  treatment as dunning's tick.
- **Full-range scans.** Reconciliation and `recentEvents` scan ranges with no partitioning.
  "Millions of events" needs partitioning/time-series/columnar storage, not H2.
- **Reconciliation detects but does not self-heal** — it flags drift; it does not
  automatically re-rollup the affected days.
- **Static, global pricing** — tiers are seeded once per metric; no per-plan or
  per-customer overrides, no prepaid credits or minimum commits.

---

## Cross-cutting design choices (and why)

| Choice | Why |
|--------|-----|
| `Money` value type, `BigDecimal` only | Rounding correctness is a type guarantee, not discipline (I1). |
| **Always invoice, never charge directly** (proration) | Keeps an auditable artifact for every money movement and makes the no-refund policy expressible (I4). |
| **DB unique constraint for idempotency** (usage) | The only idempotency that survives N concurrent writers. |
| **Rebuildable aggregates + reconciliation** (usage) | Decouples correctness from delivery order/timing; makes late events a non-event. |
| **Explicit state machine + event log** (dunning) | Legal transitions (I5) and a defensible audit timeline. |
| **Gateway seams** (`ChargeGateway`, `NotificationGateway`) | Deterministic tests; a place to add idempotency keys and outbox later. |
| **`today`/`asOf` injected time** | Replay any scenario in a test without touching the wall clock. The cost: it is not a production clock. |

The recurring theme: **the database enforces the invariants that must survive concurrency;
the application enforces the policy.** That split is what makes the scaling story in
[CONSISTENCY.md](CONSISTENCY.md) tractable.
