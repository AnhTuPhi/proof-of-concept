# ISSUE.md — What each POC is defending against

Each POC in this suite is scoped to one **specific failure mode** that has already caused real
outages, lost money, or unresolvable customer disputes in production payment systems. This file
states the failure mode in one paragraph per POC, without solution language. The `TECHNICAL.md`
file explains the shape of the fix; this file explains **why the problem exists at all**.

---

## 1. Idempotent payment — *"the network ate my response"*

**Symptom.** A client sends `POST /payments`. The server processes it, debits the card, records
the payment, then the response times out on the wire (TCP RST, load balancer 502, mobile network
drop). The client has **no way to know** whether the charge succeeded. Default client behavior is
to retry. Without protection, the second request creates a **second payment**.

**Concrete failure modes we've observed.**
- User taps "Pay" twice because the button spinner never resolved → two charges.
- Mobile SDK auto-retries with exponential backoff on socket timeout → 3–7 duplicate charges.
- Batch job restarts after crash, replays the last unfinished request → duplicate transfers.
- Two API pods receive the same retry at the same instant (client fan-out) → concurrent inserts.

**Why "just check for duplicates" doesn't work.** A duplicate check based on `(customerId, amount,
timestamp)` catches the naive case but breaks on legitimate scenarios: a user genuinely making two
identical purchases within the same minute (coffee + coffee, top-up + top-up). The system needs a
signal from the **client** that says "this is the same request as before" — an idempotency key.

**Invariant we must uphold.** For a given idempotency key, **exactly one** side effect (payment
row, external charge) occurs across the lifetime of the key, regardless of how many times the
request is retried or how many pods receive it in parallel.

---

## 2. Wallet concurrency — *"the balance went negative"*

**Symptom.** A wallet holds ₫100,000. Under normal load, everything works. Then during a flash
sale, 150 debit requests for ₫1,000 land on the wallet within 50ms. Some succeed, some fail, and
the final balance is **–₫23,000**. The company has just given away ₫23,000 for free, and
reconstruction from logs is expensive.

**Root cause.** Naive read-then-write:
```java
Wallet w = repo.findById(id);          // reads balance = 100,000
if (w.getBalance() >= amount) {        // check passes
    w.debit(amount);                   // in-memory update to 99,000
    repo.save(w);                      // overwrites with 99,000
}
```
Between the read and the save, another thread has already reduced the balance to 99,000, then
another to 98,000, etc. Both threads pass the check based on stale data. This is a **lost update**
plus a **read-modify-write race**.

**Why "just use a mutex" doesn't work.** A JVM-level lock (`synchronized`, `ReentrantLock`) works
only on a single pod. As soon as the wallet service scales to 2+ pods behind a load balancer, the
locks are on **different JVMs** and provide no coordination. The invariant must live in a place
both pods can see — the database, a distributed lock, or an atomic conditional write.

**Invariant we must uphold.** Across all pods and all concurrent debits, the wallet balance is
**never negative**, the number of successful debits **matches** the number of ledger entries,
and the ending balance **equals** the starting balance minus the sum of successful debit amounts.

---

## 3. Reconciliation — *"the numbers don't match"*

**Symptom.** At the end of the day, our internal ledger says ₫12,483,000,000 flowed through the
payment provider. The provider's settlement report says ₫12,482,650,000. That's ₫350,000
unaccounted for. Where did it go? Was it lost? Was it never actually processed? Was the provider's
fee deducted somewhere unexpected? Was there a bug in a specific transaction that we shipped but
never got settled?

**Why this is hard, not just tedious.**
- Providers report in their own timezone, batching, and settlement windows (T+1, T+2).
- Providers sometimes report the same transaction twice with a corrected amount.
- Currency conversion at the provider side can round differently than our end.
- Some transactions genuinely never settle (e.g., held for fraud review) and must be recognized
  as "waiting", not "missing".
- **The mismatch is the source of truth** for finance, tax, and audit. Ignoring it, or
  hand-waving small differences, breaks compliance and audit trails.

**Four break categories we must distinguish.**
| Break type | What it means | Who owns fixing it |
|------------|---------------|--------------------|
| `MISSING_AT_PROVIDER` | We shipped it, provider didn't settle | Ops — chase provider |
| `MISSING_AT_INTERNAL` | Provider settled, we have no record | Engineering — data loss |
| `AMOUNT_MISMATCH` | Same ref, different amount | Finance — reconcile |
| `CURRENCY_MISMATCH` | Provider settled wrong ccy | Provider bug / config |

**Invariant we must uphold.** Every settled transaction is either **matched** to an internal
record, or booked as a **specific break type** with the fields needed to resolve it. Re-running
reconciliation is **idempotent** — already-matched rows are never re-broken.

---

## 4. Refund flow — *"you owe me my money back… complicated"*

**Symptom.** A customer paid ₫500,000 gross with a ₫100,000 coupon, so we actually collected
₫400,000. Thirty days later they want a refund. The naive refund system refunds ₫500,000. Multiply
by 10,000 refunds/month and the company is bleeding money it never had. Meanwhile, a different
customer's card has expired since payment, and the refund attempt hard-fails — leaving a stranded
customer complaint that no automated retry can resolve.

**The edge cases customers actually hit.**
- **After-coupon math.** Refund the *net paid* amount, not the *face value*.
- **Partial refunds** stacked over time — the third refund must respect what the first two already
  paid out. Sum of refunds ≤ paid amount, always.
- **Expired card, closed account, removed payment method** — refund must fall back to a channel
  that will actually work (bank transfer, store credit).
- **Refund window** (typically 180 days) beyond which we cannot refund and must reject.
- **Idempotency** — refund retries must not double-refund.
- **State machine** — a settled refund cannot become "pending" again; a failed refund cannot skip
  to "settled" without going through processing.

**Invariant we must uphold.** For any original payment, `sum(refund.amount) ≤ payment.paidAmount`
at all times. Refund amounts are computed from **actual settled amount**, not requested amount.
Refund state transitions are one-way and validated at the code level.

---

## 5. Multi-currency — *"who eats the FX loss?"*

**Symptom.** Customer sees a $10 price on the store (their card is in USD). They pay. Our
settlement is in VND, so we charge $10 × 24,000 = ₫240,000. Thirty days later, the customer
requests a refund. VND has weakened; the current rate is 26,000 VND/USD. If we refund $10, our
settlement cost is $10 × 26,000 = ₫260,000 — a **₫20,000 loss** on a ₫240,000 payment. If we
refund ₫240,000 (the original settlement amount), the customer now receives only $9.23 back, and
files a complaint that we short-changed them.

**Why this is hard.**
- FX rates move every second. A quote shown at checkout is only good for a bounded window.
- The customer's presentment currency, the settlement currency, and the accounting currency may
  all be different.
- Refunds happen **later**, at a **different rate**, and this delta is real P&L.
- If FX loss/gain is mixed into transaction amounts, finance cannot separate "we earned this on
  the payment" from "we lost this on FX movement". Audit and tax become impossible.
- The customer must not care about our FX hedging. They see one currency; they pay in one
  currency; they get refunded in one currency — theirs.

**Invariant we must uphold.**
- **Rate lock.** A quote has an explicit expiry (TTL). Payments after expiry are rejected, forcing
  the client to re-quote.
- **Refund in original presentment currency at original locked rate.** The customer is made whole.
- **FX P&L delta is booked to a dedicated account.** Transaction amount is unpolluted by FX
  movement. Finance can attribute every cent to either "revenue" or "FX P&L".

---

## Cross-cutting: what all five have in common

Every POC in this suite defends the same underlying meta-invariant: **exactly-once financial
effect under adversarial conditions** (network failure, concurrency, provider disagreement,
customer behavior, currency movement). The mechanisms differ (idempotency keys, row locks,
reconciliation reports, state machines, rate locks, FX P&L segregation) but the goal is the same
— a system that a finance team can audit, a customer support agent can reason about, and a
regulator can accept.

The tests are the specification. If you change any POC, the tests describe the invariants you
must not break.
