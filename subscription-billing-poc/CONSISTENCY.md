# CONSISTENCY.md — running this behind N pods or a VM fleet

Today each POC runs as **one process** with an **in-memory H2 database**. That single-node
assumption hides every distributed-systems problem. This document is the honest answer to:
*"What breaks — and what stays correct — when we run this as a Kubernetes Deployment with
`replicas: N`, or as a fleet of VMs behind a load balancer?"*

The guiding principle from [TECHNICAL.md](TECHNICAL.md) is what makes this tractable:

> **The database enforces the invariants that must survive concurrency; the application
> enforces the policy.**

Scaling correctly is mostly a matter of pushing the right invariants down to a **shared,
transactional store** and making the **batch/clock work run exactly once**.

---

## 0. The one prerequisite: a shared database

`jdbc:h2:mem:…` is **per-process**. Two pods = two databases = two disjoint truths. Nothing
below works until every replica points at **one shared relational DB** (PostgreSQL /
Oracle). This is non-negotiable and is step zero of any deployment.

Once there is a shared DB, the work splits into two very different tiers.

---

## 1. The easy tier — stateless request handling

The controllers and services hold **no mutable in-process state** and **no HTTP session**.
Any pod can serve any request.

- ✅ **Scale horizontally freely.** `replicas: 10` behind a `Service` / load balancer needs
  no sticky sessions, no shared cache.
- ✅ **VM fleet** behind an L4/L7 balancer is equally fine.
- The only shared state is the DB, so per-request correctness reduces to
  "is this transaction safe when 10 pods run it at once?" — answered per concern below.

This is the majority of the surface area (create subscription, change plan, ingest event,
query invoices). It is already safe.

---

## 2. The hard tier — batch / clock work must run *exactly once*

Three operations are **not** per-request; they sweep the whole dataset on a schedule:

| Operation | Module | Today's trigger |
|-----------|--------|-----------------|
| `dunning.tick(today)` — run due retries, suspend, cancel | dunning | manual `POST /api/dunning/tick` |
| `rollup(customer, day)` — rebuild daily aggregates | usage | manual `POST /api/usage/rollup` |
| End-of-cycle renewal / scheduled plan swap | proration | *(not implemented)* |

If you naively add `@Scheduled` to these and deploy `replicas: N`, **all N pods fire the
job at once.** For dunning that means **N concurrent charge attempts for the same
subscription → double-charge (violates I2).**

### Option A — Externalize the schedule (recommended for a POC-to-prod path)
Run the clock **outside** the Deployment: a **Kubernetes `CronJob`** (or one dedicated
"worker" Deployment with `replicas: 1`) calls the existing endpoint / batch on a cadence.
One firing, one runner. Simple, observable, and the web tier stays purely stateless.

### Option B — Leader election inside the pods
Keep `@Scheduled` on every pod but let only the leader act:
- **ShedLock** — a lock row in the shared DB; only the pod that grabs it runs the job.
- **Quartz in clustered mode** — DB-backed job store with built-in misfire handling.
- **Kubernetes `Lease`** — native leader election via the API server.

### Option C — Partition the work
Shard by a key (e.g. `customerId % N`) so each pod owns a disjoint slice and no two pods
touch the same subscription. Scales throughput, but needs rebalancing on pod churn.

**Recommendation:** Option A to start (dead simple, exactly-once), evolve to B or C when
the batch outgrows a single runner.

---

## 3. Concurrency inside the shared DB (per invariant)

Even with exactly-once scheduling, ordinary requests race. Here is what protects each
invariant and what must be added.

### I2 — no double-charge (dunning)
- **Today:** a single instance is kept honest by `RenewalAttempt` status flips.
- **At N pods this is unsafe.** `findDueRetries` returns the same rows to every caller.
  Two runners can grab attempt #2 and both charge.
- **Fix:** claim work with a row lock — `SELECT … FOR UPDATE SKIP LOCKED` — so each due
  attempt is processed by exactly one runner, **and** send an **idempotency key** to the
  payment processor so a retried/replayed charge is deduped at the PSP too. Charging is
  a side effect that a lock alone cannot make exactly-once; the idempotency key closes the
  crash-after-charge-before-commit gap.

### I2/I3 — usage ingestion (already correct across pods)
- `event_id UNIQUE` + catch-`DataIntegrityViolationException` is the **textbook multi-writer
  idempotency pattern**. Whoever wins the insert wins; every loser treats it as a
  duplicate. This is safe for any number of pods **as-is** — no change needed. It is the
  model the other modules should copy.

### I3 — late/out-of-order events (already scale-friendly)
- Rollups are **recomputed, not incremented**, so ingestion order across pods is
  irrelevant. Many pods can ingest concurrently; a later rollup produces the correct total.
- **The one race:** `RollupService` does `delete` then `save`. A concurrent ingest landing
  between them, or two concurrent rollups of the same `(customer, day)`, can lose an event
  or collide.
- **Fix:** make the rollup an **atomic upsert** (`INSERT … ON CONFLICT DO UPDATE` /
  `MERGE`) keyed on `(customer, metric, day)`, or take a per-key advisory lock. And keep
  `ReconciliationService` running nightly — it is the **backstop that detects any drift the
  fast path missed** (I1), and could trigger an automatic re-rollup.

### I4 — credit balance (proration)
- `CustomerCredit` already carries an `@Version` column → **optimistic locking**. Two pods
  updating the same customer's balance cannot silently clobber each other; the loser gets
  an `OptimisticLockException`.
- **Fix to finish the job:** callers must **retry** on that exception. And move from a
  single mutable balance to an **append-only credit ledger** so concurrent grants are
  independent inserts (no contention) and history is auditable.

### I5 — subscription lifecycle
- Transitions are status-guarded on the entity, but "read status → decide → write" is a
  read-modify-write that two pods can interleave.
- **Fix:** guard the transition with the same row lock used for claiming work, or add an
  `@Version` to `Subscription` so an illegal concurrent transition fails loudly.

---

## 4. Side effects that must survive restarts — the outbox

Notifications (dunning emails) are sent **synchronously, in-memory**, inside the same flow
that mutates state. Across pods and restarts this is fragile:

- Crash **after** committing the state change but **before** sending → lost email.
- Naive retry of the whole flow → duplicate email (and worse, duplicate charge).

**Fix: the transactional outbox pattern.** Within the same DB transaction that changes
state, insert an `outbox` row describing the message. A separate relay (one of the pods,
or the CronJob worker) reads committed outbox rows and delivers them **at-least-once with a
dedup key**. This decouples "state changed" from "email/webhook sent" and makes both
exactly-once-effectively across the fleet.

---

## 5. Time in a distributed system

`today` / `asOf` as a request parameter is a **replay-testing convenience**, not a
production clock. Across N pods:

- The **scheduler** (§2) owns "now" — it stamps the run time once and passes it down, so
  every pod in a batch agrees on the boundary. Don't let each pod read its own
  `LocalDate.now()`.
- Cycle boundaries must be **timezone-aware** and computed from a single source. Usage
  already anchors on **UTC** (`usageDay`), which is the right call — keep billing-period
  math in one explicit zone.

---

## 6. Consistency levels — what we promise

| Data | Consistency | Mechanism |
|------|-------------|-----------|
| Subscription status, invoices, credit balance | **Strong** (within one shared-DB transaction) | ACID + row/optimistic locks |
| Raw usage events | **Strong, exactly-once storage** | `event_id` UNIQUE constraint |
| Daily rollups vs raw events | **Eventually consistent** | Rebuildable aggregate; reconciled on a schedule |
| Notifications / webhooks | **At-least-once, dedup-keyed** | Transactional outbox (to be added) |
| Charge at the PSP | **Effectively-once** | Idempotency key + local attempt record (to be added) |

Financial *state* is strongly consistent. *Derived* data (rollups) is eventually consistent
by design and continuously **reconciled** — cheaper than forcing strong consistency on a
high-volume event stream, and honest because drift is measured, not assumed absent.

---

## 7. Deployment checklist (single-node POC → N pods)

- [ ] Replace in-memory H2 with a **shared PostgreSQL/Oracle**; add Flyway/Liquibase
      migrations instead of `ddl-auto: create-drop`.
- [ ] Move `tick` / `rollup` / renewals to an **exactly-once scheduler** (k8s `CronJob` or
      leader-elected worker) — never `@Scheduled` on every replica.
- [ ] Add `SELECT … FOR UPDATE SKIP LOCKED` to due-work queries (dunning retries, rollup
      keys).
- [ ] Send an **idempotency key** to `ChargeGateway`; keep the local attempt record.
- [ ] Add `@Version` to `Subscription`; **retry** on `OptimisticLockException` for
      `CustomerCredit`.
- [ ] Make `RollupService` an **atomic upsert**; keep nightly reconciliation as the drift
      backstop.
- [ ] Introduce a **transactional outbox** for notifications/webhooks.
- [ ] Source **time** from the scheduler, not per-pod `now()`; keep period math
      timezone-explicit.
- [ ] Confirm the web tier stays **stateless** (no in-process caches that must agree across
      pods; externalize to Redis only if genuinely needed).

The good news: the two hardest correctness cores — **DB-enforced idempotent ingestion** and
**rebuildable, reconciled aggregates** — are already designed to scale. Most remaining work
is applying the *same discipline* (DB-enforced invariant + exactly-once batch) to the
charge, credit, and notification paths.
