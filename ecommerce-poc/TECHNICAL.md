# TECHNICAL.md — Solution shapes, key tech, and tech debt

For each of the five POCs this document describes:

1. **The hard part** — the concrete failure mode we're defending against.
2. **What we protect** — the invariant that must never break.
3. **Solution shape** — the small set of moving parts.
4. **Key tech by responsibility** — which tool owns which sub-problem.
5. **How it solves each sub-problem** — the exact mechanism.
6. **Tech debt we knowingly accept** — the shortcuts a real system would close.

---

## POC 1 — Inventory reservation with TTL

Package: `vn.com.ipas.poc.inventory`

### 1. The hard part
A cart is a **temporary claim** on stock, not a sale. Between "user added to
cart" and "user paid" (or "user gave up"), the unit must be held for that
user and only that user, then either committed or returned to the pool. Three
things race:

- The user's own confirm.
- The user's own explicit release.
- The sweeper reclaiming an abandoned hold.

If any two of these succeed on the same reservation, stock counts drift.

### 2. What we protect
**Every reservation reaches exactly one terminal state:** `CONFIRMED` |
`RELEASED` | `EXPIRED`. Stock returns to the pool if and only if the state is
`RELEASED` or `EXPIRED`.

### 3. Solution shape

```
   ┌──────────────┐    reserve()     ┌──────────────┐
   │   stock pool │ ────────────────▶│  ACTIVE      │
   │  AtomicInt   │◀─── sweepExpired │  reservation │──confirm──▶ CONFIRMED
   │  per SKU     │◀─── release      │  (id, ttl)   │──release──▶ RELEASED
   └──────────────┘                  └──────────────┘──expire───▶ EXPIRED
                                              ▲
                            scheduled 200ms  │  sweeper thread
```

### 4. Key tech by responsibility

| Sub-problem | Owner | Why this |
|-------------|-------|----------|
| Atomic check-and-decrement of stock | `AtomicInteger` + CAS loop | Lock-free, tiny critical section, correct under contention |
| Registry of live reservations | `ConcurrentHashMap<String, Reservation>` | Constant-time by id, no external key/value store |
| Terminal-state transitions | `ConcurrentHashMap.replace(k, oldRef, newRef)` (CAS on the map cell) | Exactly one of confirm/release/expire wins the CAS |
| Abandonment cleanup | `ScheduledExecutorService` sweeper every 200 ms | Idempotent, cheap; you can stop and restart it any time |
| Reservation shape | Java `record` with `withStatus` / `withExpiry` copy | Immutable — safe to hand around, safe to compare-and-swap |

### 5. How it solves each sub-problem
- **Oversell** — `reserve()` runs a CAS loop against `AtomicInteger`; only one
  thread ever wins for the last unit.
- **Abandonment** — the sweeper CAS-flips ACTIVE → EXPIRED and adds the qty
  back. Because the flip is CAS on the map cell, a late `confirm()` on the
  same reservation loses cleanly.
- **Idempotency** — `confirm`/`release`/`refresh` all first check the current
  status is `ACTIVE`, then CAS-swap. A duplicated request is a no-op, never a
  double-decrement or double-return.

### 6. Tech debt we acknowledge
- **Single-process state.** All state lives in memory. For a real deployment
  the stock counter and reservation map must move behind Redis (Lua for the
  CAS) or a DB row with `UPDATE ... WHERE stock >= ?`. Covered in
  [`CONSISTENCY.md`](CONSISTENCY.md).
- **No persistence.** A crash loses every ACTIVE reservation. Real systems
  write reservations to an outbox / DB so a restart can rehydrate them.
- **Sweeper is a fixed-interval poll.** Fine at the demo scale but wastes a
  tick on every SKU with no expired holds. A priority queue keyed by
  `expiresAt` would only wake up when the head is due. Ship the poll first.
- **No per-user quota.** A single user could reserve every unit. Real systems
  cap concurrent reservations per user + per IP.

---

## POC 2 — Oversell prevention strategy comparison

Package: `vn.com.ipas.poc.oversell`

### 1. The hard part
Given N concurrent buyers and finite stock, produce a scheme where **the sum
of confirmed sales never exceeds the initial stock**, and where the scheme
survives a stampede without falling over.

### 2. What we protect
`stock_after = stock_before - sum(confirmed_qty)` — as an invariant, at every
moment, for every SKU. Never negative, never off-by-one.

### 3. Solution shape
One `InventoryStrategy` interface, five implementations that race the same
harness (1,000 virtual threads, 10 units, 1 unit each). The harness reports
Sold / Stock-left / Verdict per strategy.

### 4. Key tech by responsibility

| Strategy | Owner of correctness | Owner of throughput |
|----------|----------------------|---------------------|
| Naive | — (broken by design) | — |
| Synchronized | `synchronized` monitor | serialised — throughput = 1/critical-section |
| **Atomic CAS** | `AtomicInteger.compareAndSet` loop | lock-free — losers spin briefly and retry |
| Optimistic (`@Version`) | version counter + write CAS | mediocre — write amplification under contention |
| Queue-serialised | single-threaded actor (per-SKU worker) | bounded by queue drain rate; concurrency = 1 by construction |

### 5. How it solves each sub-problem
- **Atomicity of "check + decrement"** — done inside the CAS loop /
  `synchronized` block / queue task. No two threads ever see the same
  `prev` value succeed.
- **Fairness / throughput** — atomic CAS scales because failed CAS attempts
  spin rather than park; synchronized serialises everything; the queue
  strategy trades concurrency for the ability to add rich business rules
  (per-user caps, combo checks) that don't fit in one CAS.
- **Retry visibility** — `OptimisticVersionStrategy` counts retries as a
  `LongAdder`. In real systems this is your **hot-row detector**: if
  retries/sec crosses a threshold, page whoever owns that SKU.

### 6. Tech debt we acknowledge
- **In-memory counters only.** Same story as POC 1 — Redis / DB is the
  actual shared state in prod. See [`CONSISTENCY.md`](CONSISTENCY.md).
- **The "retry cap = 100"** in the optimistic strategy is arbitrary. Real
  systems must alert on retry-cap-hit and shed load rather than fail the
  buyer silently.
- **No back-pressure signal.** All strategies say yes/no on a single buy.
  A production strategy layer would also emit "hot SKU" signals so upstream
  admission can throttle.

---

## POC 3 — Flash sale (100k requests, 100 units)

Package: `vn.com.ipas.poc.flashsale`

### 1. The hard part
The **coordination cost** blows up before the correctness bug does. Even a
correct atomic CAS on a single counter melts under 100k concurrent retries
because you're bottlenecked on one cache line. And the majority of that
traffic — bots, retry loops — was never going to convert.

### 2. What we protect
Two invariants, in order of importance:
1. **No oversell** — sold ≤ 100.
2. **The system stays responsive** — the p99 admitted-request latency stays
   in single-digit ms even at peak. If the site freezes, users assume it's
   broken and hammer refresh, and now you've DDOSed yourself.

### 3. Solution shape — two independent layers, cheapest first

```
   requests ─┐                                 ┌── ADMITTED ──▶ BucketedInventory ──▶ sold
             ▼                                 │                (16 buckets × ~6 units)
    ┌────────────────┐   admit(userId)         │
    │  AdmissionGate │────────────────────────▶│
    │  rate-limit +  │                         │
    │  virtual queue │─── DROPPED_RATE_LIMIT ──┴─▶ (bot / abuse)
    └────────────────┘─── QUEUED ──────────────▶ ("#4523 in line")
```

### 4. Key tech by responsibility

| Sub-problem | Owner | Why this |
|-------------|-------|----------|
| Drop bots / abusive clients | `AdmissionGate` per-user 1-second bucket | Cheapest possible check — no downstream work |
| Cap in-flight work | `AdmissionGate` `AtomicInteger inFlight` vs `maxInFlight` | Bounded concurrency = predictable latency |
| Split the hot key so CAS isn't serialised | `BucketedInventory` with N `AtomicInteger[]` | CAS contention drops by ~N× |
| Correct oversell prevention | CAS loop inside each bucket | Same primitive as POC 2 winner |
| Empty-detection across buckets | linear walk from a random start bucket | Cheap, cache-friendly for N ≤ 64 |
| Contention observability | `LongAdder walks` (bucket walks) | Higher walks = worse spread — actionable metric |

### 5. How it solves each sub-problem
- **Bots** — per-user token bucket at N req/sec drops them at admission.
  They never touch inventory.
- **Herd** — `maxInFlight` puts everyone else in a virtual queue. Load shed
  is deterministic, not a cascade failure.
- **CAS meltdown** — bucketing spreads decrements across N cache lines.
  Under 100k requests with 16 buckets the effective contention per bucket
  is ~6k, an order of magnitude below the meltdown point.
- **Correctness** — losing CAS in bucket i walks to bucket i+1. Once all
  buckets are 0, `tryBuy` returns false. The invariant holds because each
  bucket is independently monotonic.

### 6. Tech debt we acknowledge
- **Admission is in-process.** In production this belongs at the edge (Envoy
  / NGINX + a Redis token-bucket). See [`CONSISTENCY.md`](CONSISTENCY.md).
- **Random bucket picking.** For real workloads use `hash(userId) % N` so
  the same user consistently hits the same bucket — this also enables
  per-user idempotency in that bucket.
- **1-second sliding window** on the rate-limit bucket is coarse. A proper
  token-bucket or leaky-bucket would be smoother across boundaries. Fine
  for a bot filter, not for a friendlier per-user quota.
- **No captcha / login gate.** Real systems do the humans-only check before
  admission. Modelled but not implemented here.

---

## POC 4 — Coupon engine

Package: `vn.com.ipas.poc.coupon`

### 1. The hard part
Coupons compose. `10% + ₫50k + free ship` is one policy; `10% then ₫50k off
the discounted subtotal, then ship` is a different policy; the difference
matters to marketing, to finance, and to the customer. Whoever wrote the
first coupon loop implicitly decided the policy. Nobody documented it. Now
every new coupon type risks silently changing the answer for every past
promo.

### 2. What we protect
Two invariants:
1. **Determinism.** `apply(cart, [A, B, C])` returns the same final total
   every time, on any node, regardless of insertion order into the request.
2. **Explainability.** For any order we can reconstruct exactly which coupon
   fired, in what stage, and what discount it produced. Refunds, fraud,
   support all read from that.

### 3. Solution shape

```
   coupons ──▶ validateStackable  ──rejects if any pair conflicts──▶ error
                     │
                     ▼
              sort by Stage.order   (PERCENT → CATEGORY → FIXED → SHIP)
                     │
                     ▼
              for each c: c.apply(ctx); ctx.log(c, before - after)
                     │
                     ▼
              DiscountContext {finalTotal, audit trail}
```

### 4. Key tech by responsibility

| Sub-problem | Owner | Why this |
|-------------|-------|----------|
| Fixed application order | `Coupon.Stage` enum with `int order` | Global policy in one place; changing it is one diff |
| Compile-time coverage of coupon types | `sealed interface Coupon` + `permits` | Adding a new type without handling it is a compile error |
| Stackability | symmetric pairwise `stackableWith` | O(n²) — trivial and testable |
| Deterministic composition | `apply` reads current ctx, mutates via `applyToSubtotal` / `applyToShipping` helpers with cap-at-zero | No coupon can push a total negative; no ordering surprises |
| Explain / audit | `DiscountContext.audit` entries per coupon | Feeds UI, refunds, fraud queries |
| Auto-select best combo | `BestComboFinder` bitmask over 2^N subsets | For N ≤ 20 this is sub-ms and exact |

### 5. How it solves each sub-problem
- **Order determinism** — stage enum drives the sort. A percent coupon
  always fires before a fixed one, always before shipping.
- **Type safety** — sealed hierarchy means every switch on coupon kind is
  exhaustive. New coupon type → compile error until you handle it.
- **Compose safely** — `validateStackable` runs before any mutation. Any
  invalid combination fails fast with a clear message.
- **Cap-at-zero** — `applyToSubtotal(Math.min(discount, currentSubtotal))`.
  A generous coupon on a cheap cart can't produce negative money.
- **Best-combo** — brute force is correct, obvious, and fast enough. Under
  25 coupons we throw rather than optimize; that's a policy signal, not a
  library limitation.

### 6. Tech debt we acknowledge
- **No per-user redemption cap.** Coupons here don't track "already used
  by user X" or global caps. In production these live in Redis with atomic
  `INCR` + max-check.
- **No time-window / start-end.** Real coupons have `validFrom`, `validTo`,
  timezone rules, and blackout dates.
- **No i18n for reasons.** The audit trail is raw code / stage — production
  needs a message key so the UI can localise "10% off applied".
- **BestComboFinder is O(2^N).** Hard-capped at 25. If wallets get bigger,
  switch to stage-stratified beam search — but measure before optimizing.

---

## POC 5 — Multi-warehouse fulfillment routing

Package: `vn.com.ipas.poc.fulfillment`

### 1. The hard part
Per-line cost minimisation ≠ per-order cost minimisation. A greedy "each
line goes to its cheapest warehouse" gladly splits an order into 4 boxes to
save ₫5k of shipping — costing ops far more in handling fees, packaging,
and customer-satisfaction credits.

### 2. What we protect
- **Feasibility.** Every unit of every line is assigned to some warehouse
  that had it in stock.
- **Policy-aligned score.** The optimizer optimises what ops actually cares
  about: `cost + parcel-split penalty + out-of-region penalty`, not just
  `sum(ship_cost)`.

### 3. Solution shape

```
   Order ──▶ GreedyRouter  ──▶ Plan(shipments)   ← baseline, fast, illustrates the split problem
         │
         └─▶ OptimalRouter ──▶ Plan(shipments)   ← branch-and-bound, weighted scalar score
                                                    score = cost + (shipments-1)*SPLIT_PENALTY
```

### 4. Key tech by responsibility

| Sub-problem | Owner | Why this |
|-------------|-------|----------|
| Domain model | Java `record`s: `Warehouse`, `OrderLine`, `Order`, `Shipment`, `Plan` | Immutable, easy to reason about, easy to serialize |
| Baseline | `GreedyRouter` sorted by cost, splits when needed | Fast, correct-for-feasibility, transparent |
| Optimal | `OptimalRouter` recursive branch-and-bound | Small search space (N_warehouses ^ N_lines); prunes on lower-bound score |
| Policy tuning | `SPLIT_PENALTY` constant + region cost multiplier | One place to tune; ops can change it without a code rewrite |
| Replan on cancellation | routers are pure functions of `(order, warehouses)` | Cancel a line → recompute with the remaining lines |

### 5. How it solves each sub-problem
- **Feasibility** — greedy walks each line across warehouses cheapest-first,
  taking as much as available. If total availability < order qty it throws
  early.
- **Parcel-split control** — the optimizer's scalar score adds `(shipments -
  1) × SPLIT_PENALTY`. A single ₫50k shipment beats two ₫24k ones because
  `50k < 24k + 24k + 100k penalty`.
- **Region bias** — cost has a 1.4× multiplier when the warehouse's region
  isn't the customer's. Ties break toward same-region naturally.
- **Recomputability** — inputs are immutable records; running the router
  twice on the same inputs returns the same plan.

### 6. Tech debt we acknowledge
- **Search space** — the optimizer only explores no-split assignments first
  and falls back to greedy if none feasible. A full split-optimizer is
  much bigger and unnecessary for realistic order sizes.
- **Cost model is a demo.** `ceil(units/5) × costPerBox × region-multiplier`
  approximates parcel cost. Real ops has volumetric weight, carrier tables,
  bracket pricing, promo lanes.
- **Static SLA per warehouse.** Real SLA is `(warehouse, carrier, region,
  time-of-day)` — a lookup table, not a scalar. Fine for a POC.
- **No SLA-hard-constraint mode.** Right now SLA is only reported. A
  production optimizer needs "reject any plan whose max SLA > T hours"
  for guaranteed-delivery products.
- **Warehouse scaling.** At 50+ warehouses the exhaustive search is too
  wide. Cluster by region first, then optimize within cluster. Not done
  here.

---

## Cross-cutting technical decisions

These apply across all five POCs.

| Decision | Why |
|----------|-----|
| **Java 21 + virtual threads** (`Executors.newVirtualThreadPerTaskExecutor`) | POCs need to model thousands of concurrent buyers cheaply. Virtual threads make the code read like blocking code but scale like async. |
| **Java `record` for value types** | Immutable value semantics = safe to pass across threads, safe to CAS on identity, clean audit trails. |
| **`sealed interface` for coupon polymorphism** | Compile-time exhaustiveness. No runtime `if instanceof` chain silently missing a type. |
| **`AtomicInteger` + CAS as the default correctness primitive** | Lock-free, portable, matches the semantics of the Redis/DB equivalents (`WATCH/MULTI`, `UPDATE ... WHERE stock >= ?`) so the mental model transfers to production. |
| **`LongAdder` for high-contention counters** (walks, retries) | Correct under contention; `AtomicLong` would itself become a hot spot. |
| **`ConcurrentHashMap.replace(k, old, new)` as a state-transition CAS** | Exactly one of confirm/release/expire wins. No external locking needed. |
| **Everything in-memory, no external deps** | POCs must run with `mvn compile exec:java` and nothing else. The consistency doc explains how each in-memory primitive maps to Redis / DB / Kafka in prod. |

## Where to go next

- [`ISSUE.md`](ISSUE.md) — what each POC is defending against, in plain English.
- [`CONSISTENCY.md`](CONSISTENCY.md) — what changes when you move from one JVM
  to N pods / N VMs behind a load balancer.
- [`docs/flow.html`](docs/flow.html) — visual walkthrough of each POC.
