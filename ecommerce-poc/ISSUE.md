# ISSUE.md — What each POC is actually solving

This repository is a set of five focused POCs that isolate the **hard, contended
parts of a real e-commerce backend**. Each POC picks a single problem, races it
under load, and shows a correct-and-scalable answer next to the naive answer
that fails silently.

The five issues we address are below. Each has a **symptom** (what you see in
prod), the **root cause** (why it happens), and the **money at stake** (why we
bother writing hard code for it).

---

## Issue 1 — Cart items disappear from stock while users are "just looking"

**POC:** `inventory` — `vn.com.ipas.poc.inventory.InventoryReservationPoc`

**Symptom.** A user adds an iPhone to the cart, goes to lunch, comes back and
checks out — but ops sees inventory has "leaked": the item is still shown as
locked, no order was ever placed, and a real buyer who wanted it got told
"out of stock" during the lunch hour. Alternatively the opposite: two users
both saw stock=1, both clicked Buy, both got a confirmation, and only one unit
exists.

**Root cause.**
1. No **atomic check-and-decrement**: naive `if (stock >= n) stock -= n` has a
   TOCTOU window where a second thread reads the same value before the first
   writes.
2. No **TTL on holds**: an abandoned cart holds inventory forever. There is no
   cleanup because nobody knows the cart was abandoned.
3. No **terminal state per reservation**: without one, `confirm`, `release`,
   and `expire` race each other and the same reservation can be finalized
   twice.

**Money at stake.** Every leaked hold on a Friday-launch SKU is a real
customer who walked away. Every oversell is a manual apology, a refund, and a
chargeback.

---

## Issue 2 — We sold 12 units of a 10-unit SKU. Nobody knows how.

**POC:** `oversell` — `vn.com.ipas.poc.oversell.OversellPreventionPoc`

**Symptom.** The dashboard shows `sold=12, stock=-2`. Support inbox fills with
"where is my order?" tickets. Warehouse has 10 units. The bug reproduces once
every ten thousand requests in staging so nobody caught it.

**Root cause.** The most common **naive read-then-write** pattern:
```
if (stock >= qty) {
  // ← any work here: log line, DB round-trip, network call
  stock -= qty;
}
```
Under concurrent load, two threads both pass the `if`, both do the `-=`, and
we've oversold. Every ORM tutorial teaches this shape. It works in dev because
dev has one user.

**What we compare in this POC (1,000 virtual threads, 10 units of stock):**

| Strategy | Correct? | Scales? | When to use |
|----------|:--:|:--:|-------------|
| Naive read-then-write | NO — oversells | — | never |
| `synchronized` intrinsic lock | yes | no — throughput plateaus | low QPS, warehouse admin tools |
| **Atomic CAS** loop | **yes** | **yes** | hot SKUs, the default |
| Optimistic `@Version` | yes | mediocre — write amp under contention | shared-DB rows, general CRUD |
| Queue-serialized (1 worker per SKU) | yes | yes, easy to extend | when business rules are richer than a single CAS (per-user quota, combo caps, fraud) |

**Money at stake.** Oversells are non-recoverable trust damage plus operational
cost. Undersells (stuck under a permanent lock) are dead stock and customer
churn.

---

## Issue 3 — Black-Friday launch: 100k requests in one second for 100 units

**POC:** `flashsale` — `vn.com.ipas.poc.flashsale.FlashSalePoc`

**Symptom.** T-0 arrives. Traffic curve is a vertical line. Your app pool goes
to 100% CPU on inventory locks, downstream services (payment, notification)
start timing out, and the database gets a thundering-herd of `SELECT ... FOR
UPDATE` because everyone bypassed the cache to check "am I too late?".

**Root cause.** Two independent failures:
1. **Single-hot-key inventory.** Every request contends on one cache line, one
   row, one lock. Doesn't matter how correct your CAS is — 100k concurrent
   CAS retries on one counter is a coordination catastrophe.
2. **No admission control.** Bots and runaway clients spam retries the moment
   they see a rejection. 90%+ of the requests you're processing are already
   losers; they should never have reached inventory.

**Solution shape.** Two independent layers, cheapest first:
- **AdmissionGate** — per-user rate limit (bots die at the edge) plus a
  virtual queue (only K requests in flight at once).
- **BucketedInventory** — the 100 units are pre-split into N buckets. A buy
  picks a random bucket, CAS-decrements, walks to the next bucket on failure.
  CAS contention drops by roughly N× vs a single counter.

**Money at stake.** Flash-sale reputation. If the site crashes, PR calls it
"Amazon's worst launch." If it doesn't crash but oversells, refunds and
brand damage. If it doesn't crash and doesn't oversell but takes 30 seconds
to respond, users assume it's broken and F5 the site — which is exactly the
retry storm you were trying to prevent.

---

## Issue 4 — "Why did my ₫50k coupon only take off ₫45k?"

**POC:** `coupon` — `vn.com.ipas.poc.coupon.CouponEnginePoc`

**Symptom.** Marketing runs a promo. A support agent gets 40 tickets that all
say "the discount didn't apply / is wrong / disappeared at checkout". The
engineer on-call reads the code, sees `total = total * 0.9 - 50000 - shipping`,
and has no idea whether that's right — because it depends on which coupon fired
first, and the order was implicit in the loop.

**Root cause.**
1. **Order of application is undefined.** `10% off + ₫50k off + free ship`
   applied in different orders produces different totals. Users notice.
2. **Stackability is coded ad-hoc.** `if (coupon.isPercent && other.isPercent)`
   is scattered across 6 files.
3. **No audit trail.** Refunds and fraud investigations have no way to
   answer "what actually happened to this order at checkout?"

**Solution shape.**
- A **sealed hierarchy** of coupon types (percent, fixed, category-percent,
  free-shipping).
- A **fixed stage order** encoded as an enum (`PERCENT_ON_SUBTOTAL` → 
  `CATEGORY_PERCENT` → `FIXED_AMOUNT` → `SHIPPING`). Change the enum, change
  the policy — globally, in one place.
- A **symmetric `stackableWith`** pair check on all coupon combinations
  before application.
- An **audit trail** entry per coupon: code, stage, discount amount. Shows
  in the UI, stored with the order for refund/fraud review.
- A **BestComboFinder** that brute-forces subsets of the user's wallet to
  find the maximum discount. For ≤20 coupons this is sub-ms and exact —
  simpler than any greedy approximation and no worse in practice.

**Money at stake.** Bad coupon UX is directly measurable: cart abandonment
right after coupon-entry, refund rate, support cost. Coupon fraud (stacking
that shouldn't have been allowed) is money out the door.

---

## Issue 5 — One order gets split into four boxes and takes a week to arrive

**POC:** `fulfillment` — `vn.com.ipas.poc.fulfillment.FulfillmentRoutingPoc`

**Symptom.** A South-region customer orders 4 items. System routes each line
to the cheapest warehouse for that one line. Result: parcel from HN, parcel
from DN, two parcels from HCM. Shipping cost per order is technically minimized
but the customer gets a spread-out delivery, ops handles 4 boxes instead of 1,
and the "cheapest" plan ate a ₫20k parcel-handling fee we didn't put in the
optimizer.

**Root cause.** Greedy per-line optimization ignores **structural cost**:
the number of shipments. A "cost function" that only counts VND-per-box is
not the cost function ops actually cares about.

**Solution shape.**
- Model the plan as `Plan(List<Shipment>)` where a shipment is one warehouse
  and one bundle of SKUs.
- Two routers side by side:
  - **GreedyRouter** — fast, per-line cheapest, illustrates the parcel-split
    problem.
  - **OptimalRouter** — branch-and-bound with a weighted scalar
    `score = totalCost + (shipments - 1) * SPLIT_PENALTY`. The penalty
    reflects the actual ops policy: "we'd rather pay ₫20k more than send
    two boxes."
- Region penalty on the cost function so out-of-region shipments naturally
  lose.
- Recomputable — partial cancellations just re-run the router on the
  remaining lines.

**Money at stake.** Every extra shipment is real money: extra label, extra
handling, extra last-mile fee, extra "where is my package" ticket. SLA
misses on high-value orders trigger promo credits. Multi-warehouse routing
is one of the highest-leverage cost levers in the fulfillment P&L.

---

## What is deliberately NOT in scope

- Payments, PSPs, PCI-DSS
- Full order state machine (`CART → PLACED → PAID → PICKED → SHIPPED → …`)
- Search, ranking, personalisation
- Full auth, identity, session

Each of those deserves its own repo. These five POCs concentrate on the
mechanics that go wrong under load and cost real money — the parts a senior
engineer is expected to get right without a whiteboard.
