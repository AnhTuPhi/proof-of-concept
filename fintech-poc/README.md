# Fintech POC Suite

Five self-contained Spring Boot 3.4 / Java 21 POCs demonstrating the load-bearing patterns behind
payment systems. Each module is independently runnable and ships with tests that prove the
pattern works under stress (concurrency, retries, edge cases).

| # | Module | What it shows | Endpoint | Port |
|---|--------|---------------|----------|------|
| 1 | `idempotent-payment-poc`    | Idempotency-Key header, replay-safe DB record, payment state machine, race-condition handling | `POST /payments` | 8081 |
| 2 | `wallet-concurrency-poc`    | Three concurrency strategies (pessimistic lock, optimistic version, conditional UPDATE) over a wallet + append-only ledger | `POST /wallets/{id}/debit` | 8082 |
| 3 | `reconciliation-poc`        | Internal-vs-provider matching with four break types (missing-at-provider, missing-at-internal, amount, currency) | `POST /recon/run` | 8083 |
| 4 | `refund-flow-poc`           | Refund state machine, partial/full, expired-card fallback, after-coupon math, idempotency key | `POST /refunds` | 8084 |
| 5 | `multi-currency-poc`        | FX rate lock with TTL, refund in original currency at original rate, FX P&L isolated in dedicated account | `POST /fx/quotes`, `POST /fx/payments`, `POST /fx/payments/{id}/refund` | 8085 |

All modules use H2 in-memory storage so the build is fully portable — no Docker required.

## Companion documents

Deeper documentation is split by concern:

| Doc | Purpose |
|-----|---------|
| [ISSUE.md](ISSUE.md) | For each POC — the specific failure mode being defended against, in production terms |
| [TECHNICAL.md](TECHNICAL.md) | Solution shape, key tech by responsibility, sub-case coverage, and tech debt to acknowledge |
| [CONSISTENCY.md](CONSISTENCY.md) | What breaks when scaling to K8s / multiple VMs — and how to fix it |
| [demo.html](demo.html) | Interactive visual walkthrough of each POC's flow and the tech behind it |

## Build everything

```bash
cd fintech-poc
mvn clean install
```

## Run the tests for a single POC

```bash
mvn -pl idempotent-payment-poc -am test
mvn -pl wallet-concurrency-poc -am test
mvn -pl reconciliation-poc -am test
mvn -pl refund-flow-poc -am test
mvn -pl multi-currency-poc -am test
```

## Run a POC as a live server

```bash
mvn -pl idempotent-payment-poc spring-boot:run
# then in another shell:
curl -X POST http://localhost:8081/payments \
  -H 'Idempotency-Key: req-001' \
  -H 'Content-Type: application/json' \
  -d '{"amount":100000,"currency":"VND","customerId":"cust-1"}'
```

---

## 1. Idempotent payment (`idempotent-payment-poc`)

**The Stripe pattern.** A client retries because the network ate the response — we must not double-charge.

**Mechanics**
- Client sends `Idempotency-Key: <uuid>` on every `POST /payments`.
- `IdempotencyRecord` is keyed on that header, with a `UNIQUE` constraint. The first writer wins.
- Concurrent calls with the same key race on the insert. The loser reads the winner's `IN_FLIGHT` row and is told to retry (HTTP 409). When the winner finishes, it stores the JSON response on the record; subsequent calls replay the same response (`replayed=true`).
- Same key + **different** body → 409 conflict (request fingerprint mismatch).
- The payment itself walks a state machine: `PENDING → AUTHORIZED → CAPTURED → SETTLED`. Illegal transitions throw.

**The test that matters** — `IdempotencyTest#concurrentSameKey_onlyOnePaymentCreated`: 20 threads hammer the same key concurrently. Assertion: exactly **one** payment row is created, every successful caller sees the same `paymentId`.

## 2. Wallet concurrency (`wallet-concurrency-poc`)

**Three strategies. One correctness bar.** Given 100k VND in a wallet, 100 concurrent 1k debits must succeed exactly 100 times and leave the balance at 0 — never negative.

| Strategy | Mechanism | When to use |
|----------|-----------|-------------|
| `PESSIMISTIC` | `SELECT ... FOR UPDATE` row lock | Few hot rows, contention is expected, simple to reason about |
| `OPTIMISTIC`  | `@Version` field; retry with exponential backoff on `OptimisticLockException` | Low contention, want high throughput, can tolerate retries |
| `CONDITIONAL_UPDATE` | `UPDATE wallets SET balance = balance - ? WHERE id = ? AND balance >= ?` atomic | High contention, lowest latency — the DB enforces the invariant directly |

**The test that matters** — `WalletConcurrencyTest#overdrawRejection_conditionalUpdate`: 150 attempts of 1k against a 100k wallet. Assertion: exactly **100** succeed, **50** are rejected, balance is **0** (never negative), ledger has **100** debit entries (one per success).

All three strategies are validated by the same test contract — pick the one whose throughput/complexity trade-off matches your workload.

## 3. Reconciliation (`reconciliation-poc`)

**Match your internal ledger with what the payment provider actually settled.** Daily run, four break categories.

**Mechanics**
- `InternalTxn` records what we think happened. `ProviderTxn` records what the provider says happened.
- `ReconciliationService.runDailyReconciliation()` joins on `providerRef` and checks: currency match → amount match → settlement window (48h).
- Mismatches become `ReconciliationBreak` rows of type:
  - `MISSING_AT_PROVIDER` — we shipped it, they didn't settle it
  - `MISSING_AT_INTERNAL` — they settled something we never recorded
  - `AMOUNT_MISMATCH` — same ref, different amount (rounding, partial captures, refunds)
  - `CURRENCY_MISMATCH` — provider settled in the wrong ccy
- Already-matched rows are skipped on subsequent runs (idempotent).

**The test that matters** — `ReconciliationTest#runReconciliation_matchesHappyPath_andDetectsAllBreakTypes`: a mixed input of clean matches and one example of each break type. Assertion: 2 clean matches + exactly 1 break of each of the 4 types.

## 4. Refund flow (`refund-flow-poc`)

**Refund 30 days later. Card expired. Coupon applied. Customer wants $10 back — but the original payment was $9 net of a $1 coupon.**

**Edge cases covered**
- **Partial refunds** that sum up to the paid amount, with the last one consuming the remainder.
- **After-coupon math** — refund the paid amount, not the face value. (₫500 cart with ₫100 coupon → full refund is ₫400, never ₫500.)
- **Expired payment method** → fallback to bank transfer.
- **Removed payment method** → fallback to store credit.
- **Refund window expiry** (180 days) → reject.
- **Over-refund** → reject (cumulative refunded > paid).
- **Idempotency** — same key → same refund row, no double payout.

**The test that matters** — `RefundFlowTest#refundAfterCoupon_refundsActualPaidAmount_notFaceValue`: ₫500 gross, ₫100 coupon, full refund returns ₫400. This is the rule that catches people every time.

## 5. Multi-currency (`multi-currency-poc`)

**Customer browses in USD, pays in VND, asks for a refund 30 days later when the rate has moved. Who eats the difference?**

**Mechanics**
- `FxService.quote(USD, VND)` returns a quote with the **market rate minus a margin** (we earn the margin, recorded in a dedicated `FxPnlEntry`).
- The quote has a TTL (15 min). Payments after expiry are rejected.
- `payAgainstQuote` records the locked rate on the `FxPayment` itself.
- `refundOriginalCurrency` — the killer rule: **refund the customer in the original presentment currency at the original locked rate.** The FX delta between locked rate and current market rate is booked to a separate `FxPnlEntry` of kind `FX_DELTA_REFUND`. This isolates FX P&L from payment amounts so finance can see exactly where money came from and went.

**The test that matters** — `MultiCurrencyTest#purchaseInUSD_paidInVND_refundedLaterAtOriginalRate_PnLAbsorbed`: $10 paid at 24,000 VND/USD, refunded after VND weakens to 26,000. Assertions: customer gets $10 back (not VND), our settlement cost uses the locked 24k rate, the FX loss is non-zero and booked separately.

---

## Notes on the design

- **Every POC carries an idempotency key.** Payments, refunds, and reconciliation runs are all designed to be safely retried. This is the single most important invariant in payment systems.
- **State machines are explicit.** Payments and refunds both reject illegal transitions in code (`canTransition`). This catches bugs in the integration layer that would otherwise cause stuck or corrupt records.
- **Money is `BigDecimal`, never `double`.** `Currency.scale()` drives rounding. `Money.of(...)` enforces it on construction.
- **The wallet uses an append-only ledger.** The `balance` column is a cache; the truth is the sum of ledger entries. This is the foundation for double-entry bookkeeping.
- **FX P&L is a separate account.** Never let rate fluctuation contaminate transaction amounts — finance can't unwind that later.

## What's deliberately out of scope

- No outbox / Kafka — that's covered by `cdc-outbox-poc` in this workspace.
- No external provider integration — mock/in-process.
- No auth — focus is on the financial invariants.
- No distributed tracing — each POC is a single process for clarity.

## Where to go next

- **"Why does this POC exist?"** → [ISSUE.md](ISSUE.md) explains the concrete failure modes each
  POC is defending against, with real production examples.
- **"How does it actually work, and where's the tech debt?"** → [TECHNICAL.md](TECHNICAL.md) has
  the solution shape, key tech by responsibility, sub-case coverage, and honest tech debt.
- **"What happens when I deploy this on Kubernetes with autoscaling?"** →
  [CONSISTENCY.md](CONSISTENCY.md) walks through what breaks at scale, in the order it will bite
  you, and the concrete K8s topology to fix it.
- **"Show me the flow"** → open [demo.html](demo.html) in a browser for an interactive visual
  walkthrough.
