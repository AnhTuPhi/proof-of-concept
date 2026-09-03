# TECHNICAL.md — Solution shape, key tech, and honest tech debt

For each POC, this file names:

1. **The hard problem** — the specific correctness or consistency guarantee.
2. **What we are protecting** — the invariant that must hold, and the money/state at risk.
3. **Solution shape** — the pattern, in prose and pseudocode.
4. **Key tech by responsibility** — which piece of tech carries which part of the invariant.
5. **How the solution handles each sub-problem** — one row per sub-case from `ISSUE.md`.
6. **Tech debt to acknowledge** — what's not production-ready, and why we accepted it in the POC.

The point is to make the design **defensible**, not perfect. Everything below is deliberately
small enough to fit in one head — because in a payment incident, that's the only person you have.

---

## 1. Idempotent payment (`idempotent-payment-poc`)

### Hard problem
Given N concurrent retries of the same request from anywhere in the world, produce **exactly one**
side effect and return **the same response** to every caller.

### What we are protecting
- The customer's account balance from double-charge.
- The company's outbound transfers from double-payout.
- The audit trail: every retry must be able to prove which original request it corresponds to.

### Solution shape

```
┌────────────────────────────────────────────────────────────────────────┐
│ 1. Client sends POST /payments with Idempotency-Key: <uuid>            │
│ 2. Server computes fingerprint = SHA-256(canonical(request-body))      │
│ 3. Server tries INSERT into idempotency_record (UNIQUE on key).        │
│    ├── Success → this pod is the "winner"; proceed with charge         │
│    └── DataIntegrityViolation → someone else won; read their record    │
│ 4. Winner executes payment, writes response JSON to record.status=DONE │
│ 5. All subsequent callers read the record and replay the response.     │
│ 6. Same key + different body → 409 (fingerprint mismatch, tamper).     │
│ 7. In-flight collisions → 409 with "retry shortly"                     │
└────────────────────────────────────────────────────────────────────────┘
```

### Key tech by responsibility

| Responsibility | Tech | Where |
|---|---|---|
| First-writer-wins race arbitration | DB `UNIQUE` constraint on `idempotency_key` | [`IdempotencyRecord`](idempotent-payment-poc/src/main/java/com/example/fintech/payment/IdempotencyRecord.java) |
| Detect same-key-different-body tampering | SHA-256 fingerprint of canonicalized JSON | [`IdempotencyHasher`](common/src/main/java/com/example/fintech/common/IdempotencyHasher.java) |
| Isolation of claim vs. business tx | Spring `Propagation.REQUIRES_NEW` | [`PaymentService.claimKey`](idempotent-payment-poc/src/main/java/com/example/fintech/payment/PaymentService.java) |
| Payment state machine | Enum + explicit `canTransition(...)` | [`Payment`](idempotent-payment-poc/src/main/java/com/example/fintech/payment/Payment.java), `PaymentStatus` |
| Response replay | Stored `response_json` on the record | `IdempotencyRecord.responseJson` |

### How the solution handles each sub-problem

| Sub-case (from `ISSUE.md`) | Mechanism |
|---|---|
| Client retries after network timeout | Same key → replay stored response, `replayed=true` |
| Two pods receive the same retry simultaneously | `UNIQUE` constraint; loser reads winner's row |
| Winner crashed mid-charge (record stuck `IN_FLIGHT`) | `failClaim()` marks it FAILED on exception; caller uses a fresh key |
| Client accidentally reuses key with different body | Fingerprint mismatch → 409, no charge |
| User double-taps "Pay" (client generates two different keys) | Not our problem — that's client-side idempotency (a debounce or button-lock) |

### Tech debt to acknowledge

- **No TTL on idempotency records.** In production, records need cleanup (7–30 days). We rely on
  H2 in-memory reset per test.
- **`IN_FLIGHT` stuck records need a janitor.** If the winner pod dies before writing the response,
  the record sits IN_FLIGHT forever, blocking retries. Production needs a heartbeat or a scheduled
  sweeper that marks stale IN_FLIGHT rows as FAILED after N minutes.
- **Fingerprint uses default Jackson field ordering.** Two clients that produce logically-equal but
  key-order-different JSON bodies get different fingerprints. Real systems canonicalize (sorted
  keys, normalized numbers) before hashing.
- **No response for FAILED state that carries the underlying error.** We return a generic 409 asking
  the caller to use a new key. Production may want to store the error code so clients can
  distinguish "retry with new key" from "user error, don't retry".

---

## 2. Wallet concurrency (`wallet-concurrency-poc`)

### Hard problem
Given a wallet with balance B and N concurrent debit requests each for amount `a_i`, ensure that
at most K debits succeed where K is the largest set such that `sum(a_i for i in successes) ≤ B`,
the ending balance is exactly `B − sum(successful a_i)`, and the ledger has one entry per
successful debit — under any interleaving, on any number of pods.

### What we are protecting
- The wallet's non-negativity invariant. Negative balance = free money given away.
- The double-entry ledger's completeness. One successful debit = exactly one ledger row.

### Solution shape

Three strategies validated by the **same test contract**. Pick per workload:

```
┌─────────────────────┬───────────────────────────────────┬──────────────────────────┐
│ Strategy            │ How it enforces the invariant     │ Cost                     │
├─────────────────────┼───────────────────────────────────┼──────────────────────────┤
│ PESSIMISTIC         │ SELECT ... FOR UPDATE row lock;   │ Serializes hot rows,     │
│                     │ subsequent readers block.         │ good for high contention │
├─────────────────────┼───────────────────────────────────┼──────────────────────────┤
│ OPTIMISTIC          │ @Version column; save fails if    │ Retries under contention │
│                     │ version changed; retry+backoff.   │ waste CPU. Best at low   │
│                     │                                   │ contention.              │
├─────────────────────┼───────────────────────────────────┼──────────────────────────┤
│ CONDITIONAL_UPDATE  │ UPDATE ... WHERE balance >= amt.  │ One round-trip. Lowest   │
│                     │ Rows-affected = 0 → rejected.     │ latency. No app-level    │
│                     │                                   │ retries needed.          │
└─────────────────────┴───────────────────────────────────┴──────────────────────────┘
```

### Key tech by responsibility

| Responsibility | Tech | Where |
|---|---|---|
| Row-level serialization | `@Lock(PESSIMISTIC_WRITE)` | [`WalletRepository.findByIdForUpdate`](wallet-concurrency-poc/src/main/java/com/example/fintech/wallet/WalletRepository.java) |
| Optimistic version | JPA `@Version` + retry loop with jitter | [`Wallet`](wallet-concurrency-poc/src/main/java/com/example/fintech/wallet/Wallet.java), [`OptimisticLockStrategy`](wallet-concurrency-poc/src/main/java/com/example/fintech/wallet/strategies/OptimisticLockStrategy.java) |
| Atomic conditional write | `UPDATE ... WHERE balance >= :amount` | [`WalletRepository.conditionalDebit`](wallet-concurrency-poc/src/main/java/com/example/fintech/wallet/WalletRepository.java) |
| Append-only truth | `LedgerEntry` (immutable rows) | [`LedgerEntry`](wallet-concurrency-poc/src/main/java/com/example/fintech/wallet/LedgerEntry.java) |
| Money math correctness | `BigDecimal` everywhere, never `double` | [`Money`](common/src/main/java/com/example/fintech/common/Money.java) |

### How the solution handles each sub-problem

| Sub-case | Mechanism |
|---|---|
| Two threads read balance simultaneously, both decrement | Pessimistic: second blocks. Optimistic: second's `save` throws `OptimisticLockException` → retry. Conditional: second `UPDATE` matches 0 rows → reject. |
| Successful debit but ledger insert fails | Same `@Transactional` — both commit or neither. |
| Debit larger than balance | All three refuse: pessimistic sees the check under the lock; optimistic reads the stale value but retries and eventually sees the true value; conditional's WHERE excludes the row. |
| Balance is a cache, not the truth | Ledger sum is the reconciled truth; the `balance` column is a materialized aggregate. A background job could verify `sum(ledger) == balance` and alarm on drift. |

### Tech debt to acknowledge

- **Ledger has no double-entry counterpart.** A real ledger records BOTH sides of every
  transaction (debit + credit). We record only the wallet side. Production needs a `journal_entry`
  table with debit/credit rows that always sum to zero.
- **No idempotency on debit itself.** The POC assumes upstream (the caller) provides idempotency.
  Real wallets combine idempotency-key + conditional update so retries don't double-spend.
- **Balance drift detection is missing.** No scheduled job that recomputes
  `SUM(ledger.amount) == wallet.balance`. In production this is the alarm that catches every
  race condition you didn't predict.
- **Optimistic retry limit is 64 with exponential backoff.** Under sustained contention this can
  starve. Real systems either add a queue in front (single-writer per wallet) or fall through to
  pessimistic after N failures.

---

## 3. Reconciliation (`reconciliation-poc`)

### Hard problem
Given two independent streams of records (internal transactions, provider settlements), produce a
**complete, categorized diff** that every downstream consumer (finance, ops, audit) can act on.
Running it twice must not produce two copies of every break.

### What we are protecting
- The truth-of-cash-flow that finance closes the books against.
- The audit trail. Every break must have a category, both sides' IDs, and a human-readable
  reason string.
- Idempotency of the recon run itself — matched rows stay matched.

### Solution shape

```
┌───────────────────────────────────────────────────────────────────────┐
│ 1. For each unmatched InternalTxn:                                    │
│    a. Look up ProviderTxn by providerRef                              │
│    b. If missing → MISSING_AT_PROVIDER break                          │
│    c. If currency differs → CURRENCY_MISMATCH break                   │
│    d. If amount differs → AMOUNT_MISMATCH break                       │
│    e. If gap > 48h → break (settlement window)                        │
│    f. Otherwise → mark BOTH sides matched, count as clean             │
│                                                                       │
│ 2. For each unmatched ProviderTxn:                                    │
│    a. If no InternalTxn with that providerRef → MISSING_AT_INTERNAL   │
│                                                                       │
│ 3. Return report: (cleanCount, breakCount, openBreaks)                │
└───────────────────────────────────────────────────────────────────────┘
```

### Key tech by responsibility

| Responsibility | Tech | Where |
|---|---|---|
| Match key | `providerRef` column (indexed) | [`InternalTxn`](reconciliation-poc/src/main/java/com/example/fintech/recon/InternalTxn.java), [`ProviderTxn`](reconciliation-poc/src/main/java/com/example/fintech/recon/ProviderTxn.java) |
| Idempotency of run | `matched` boolean, only re-process where `matched=false` | [`ReconciliationService`](reconciliation-poc/src/main/java/com/example/fintech/recon/ReconciliationService.java) |
| Break categorization | `BreakType` enum, one row per break | [`ReconciliationBreak`](reconciliation-poc/src/main/java/com/example/fintech/recon/ReconciliationBreak.java) |
| Ordering-independent join | Two passes: internal→provider, then provider→internal | `ReconciliationService.runDailyReconciliation` |

### How the solution handles each sub-problem

| Sub-case | Mechanism |
|---|---|
| Same recon run three times in a day | Only rows with `matched=false` are re-examined; already-matched rows are inert. |
| Provider reports a currency we didn't send | `CURRENCY_MISMATCH` break with both currency codes in the reason. |
| Provider reports a slightly different amount (rounding) | `AMOUNT_MISMATCH` with `abs(a-b)` in reason. Downstream decides whether to auto-approve. |
| Settlement outside 48h window | Break marked with the actual gap. Ops has enough info to widen the window if it's a policy issue. |
| Provider settled a transaction we have no record of | `MISSING_AT_INTERNAL` — the highest-severity break; investigate data loss. |

### Tech debt to acknowledge

- **No auto-resolution.** All breaks land as `OPEN`; a human must clear them. Real recon has
  auto-rules ("if amount diff < ₫100, auto-approve as rounding") to reduce ops toil.
- **`OUTSIDE_WINDOW` is bucketed under `AMOUNT_MISMATCH`.** They're distinct root causes and should
  be distinct enum values.
- **No T-1 vs T+1 handling.** We use a single 48h window; real providers have per-instrument
  windows (card 2 days, bank 3–5 days).
- **The recon join is O(N) linear scan + per-row DB lookup.** At 10M internal txns/day this must
  become a set-based SQL join (or Spark job) with a temp table and window functions.
- **No partial run resumability.** If the process crashes at row 400k of 1M, we start over from
  the top on next run. Production would checkpoint per batch.

---

## 4. Refund flow (`refund-flow-poc`)

### Hard problem
Given an original payment and a refund request, compute the correct refund amount, pick a valid
payout channel, validate against the remaining refundable amount, respect a state machine, and
make the operation idempotent — such that the sum of all refunds never exceeds the actual
collected amount.

### What we are protecting
- The company from over-refund (refunding more than we collected).
- The customer from under-refund (short-changed when payment method broke).
- The audit trail from illegal state transitions (`SETTLED → PENDING` etc.).

### Solution shape

```
┌────────────────────────────────────────────────────────────────────────┐
│ POST /refunds { idempotencyKey, paymentId, requestedAmount | full }    │
│                                                                        │
│  1. If refund with idempotencyKey exists → return it (replay)          │
│  2. Load payment; reject if refund window (180d) expired               │
│  3. Compute amount:                                                    │
│      - full         → remainingRefundable (actual paid − sum(refunds)) │
│      - partial      → validate positive and ≤ remainingRefundable      │
│  4. Pick channel:                                                      │
│      - ACTIVE       → ORIGINAL_CARD                                    │
│      - EXPIRED      → BANK_TRANSFER                                    │
│      - REMOVED      → STORE_CREDIT                                     │
│  5. Insert refund row (UNIQUE on idempotencyKey)                       │
│  6. Walk state: CREATED → PROCESSING → SETTLED                         │
│  7. Update payment.refundedAmount                                      │
└────────────────────────────────────────────────────────────────────────┘
```

### Key tech by responsibility

| Responsibility | Tech | Where |
|---|---|---|
| Idempotency | `UNIQUE` on `refund.idempotency_key` | [`Refund`](refund-flow-poc/src/main/java/com/example/fintech/refund/Refund.java) |
| After-coupon math | `OriginalPayment.paidAmount` (net) as source of truth, not `faceValue` | [`OriginalPayment`](refund-flow-poc/src/main/java/com/example/fintech/refund/OriginalPayment.java) |
| Partial refund cap | `remainingRefundable = paid - refunded`, checked before insert | `OriginalPayment.remainingRefundable` |
| Channel fallback | `MethodStatus` enum → `RefundChannel` mapping | [`RefundService.pickChannel`](refund-flow-poc/src/main/java/com/example/fintech/refund/RefundService.java) |
| State machine | `Refund.canTransition(from, to)` | `Refund.RefundStatus` |
| Refund window | `Duration.between(capturedAt, now) > 180d` reject | `RefundService.createRefund` |

### How the solution handles each sub-problem

| Sub-case | Mechanism |
|---|---|
| Refund amount = face value bug | We refund `remainingRefundable`, computed from `paidAmount`, never from `faceValue`. |
| Two partial refunds that together exceed paid | Second refund's `computeRefundAmount` sees updated `remainingRefundable`; rejects. |
| Card expired between payment and refund | `pickChannel` returns `BANK_TRANSFER`; refund proceeds. |
| Payment method deleted (GDPR / user closed account) | `pickChannel` returns `STORE_CREDIT`. |
| Refund attempted 6 months later | `REFUND_WINDOW = 180d`; explicit reject with error message. |
| Same idempotencyKey retried | `findByIdempotencyKey` short-circuit; race is caught by unique-constraint fallback. |
| Illegal state transition attempted | `canTransition` throws before the row is saved. |

### Tech debt to acknowledge

- **No async settlement.** Real refunds go through the provider asynchronously; we walk the state
  machine synchronously to end. Production needs `PROCESSING` as the real end state and a
  webhook/poller to transition to `SETTLED`.
- **Store-credit fallback has no store-credit account.** In production this creates a store-credit
  balance on the customer profile with an expiry.
- **Refund window is hardcoded to 180 days.** In reality it depends on payment method, card
  network, and region.
- **Partial-then-full ordering not enforced.** A customer could partial-refund ₫100, then request
  another partial ₫100, then a "full" — full computes correctly because it uses
  `remainingRefundable`, but ops may want to reject "full" after any partial to avoid confusion.
- **No refund-side reconciliation.** Refunds should be re-checked against provider refund reports
  the same way payments are; the POC leaves that out.

---

## 5. Multi-currency (`multi-currency-poc`)

### Hard problem
The customer must see stable pricing in their currency, be charged consistently, and receive a
refund in their currency at the **original** rate — while our internal accounting cleanly
separates payment revenue from FX P&L.

### What we are protecting
- The customer's trust: they see USD, they pay USD, they refund USD.
- Finance's ability to attribute every VND of P&L to either transactions or FX movement.
- The rate lock: a quote's rate is a promise, and expired promises are void.

### Solution shape

```
┌────────────────────────────────────────────────────────────────────────┐
│ POST /fx/quotes                                                        │
│   rate = marketRate(from, to) * (1 - MARGIN)                           │
│   quote.expiresAt = now + 15min                                        │
│                                                                        │
│ POST /fx/payments  { quoteId, presentmentAmount }                      │
│   if quote.isExpired() → reject                                        │
│   payment.settlementAmount = presentmentAmount * quote.rate            │
│   FxPnlEntry(MARGIN, marketRate*presentment - settlement)              │
│                                                                        │
│ POST /fx/payments/{id}/refund  { presentmentRefundAmount }             │
│   settlementRefund = presentmentRefundAmount * ORIGINAL_LOCKED_RATE    │
│   fxDelta = settlementAtMarketRate - settlementRefund                  │
│   FxPnlEntry(FX_DELTA_REFUND, fxDelta)                                 │
│                                                                        │
│ Customer receives: presentmentRefundAmount in ORIGINAL currency.       │
└────────────────────────────────────────────────────────────────────────┘
```

### Key tech by responsibility

| Responsibility | Tech | Where |
|---|---|---|
| Rate lock with TTL | `FxQuote.expiresAt`, `isExpired()` | [`FxQuote`](multi-currency-poc/src/main/java/com/example/fintech/fx/FxQuote.java) |
| Margin capture | Multiply market rate by `(1 - MARGIN)`, book delta as `MARGIN` P&L | [`FxService.quote`](multi-currency-poc/src/main/java/com/example/fintech/fx/FxService.java) |
| Refund in original currency at original rate | Store `lockedRate` on `FxPayment`; refund uses it | [`FxPayment`](multi-currency-poc/src/main/java/com/example/fintech/fx/FxPayment.java) |
| FX P&L isolation | Dedicated `FxPnlEntry` table with `kind` column | [`FxPnlEntry`](multi-currency-poc/src/main/java/com/example/fintech/fx/FxPnlEntry.java) |
| Currency-aware money math | `BigDecimal` with per-currency scale | [`Money`](common/src/main/java/com/example/fintech/common/Money.java), `Currency` |

### How the solution handles each sub-problem

| Sub-case | Mechanism |
|---|---|
| Rate moves between quote and pay | Rate is locked on the quote row; payment uses `quote.rate`. |
| Payment after quote expiry | `isExpired()` check; reject. |
| Refund after rate has moved | `refundOriginalCurrency` uses `original.lockedRate` for settlement, current market rate only for booking the FX P&L delta. |
| We earn margin but lose on FX delta | Both booked separately: `MARGIN` and `FX_DELTA_REFUND` are distinct kinds. Net = actual P&L. |
| Customer wants refund in VND instead of USD | Not supported in POC — presentment currency is fixed. Production would need a policy: refund original ccy by default, allow force-override with a fresh quote. |

### Tech debt to acknowledge

- **`FxRateProvider` is an in-memory mock.** Production plugs in a real market data feed (Reuters,
  Bloomberg, or the treasury desk) with heartbeat and staleness detection.
- **Quote TTL is not enforced on the provider side.** If our clock drifts vs. the market, we may
  honor a quote that should have expired. Production needs a stricter clock and an audit log of
  each rate decision.
- **No hedge tracking.** Real multi-currency operations hedge FX exposure with forwards or spots.
  Booking the `FX_DELTA_REFUND` is only half the picture — the other half is the hedge that
  offsets it.
- **Margin is a fixed 0.5%.** Production varies margin per currency pair, per customer tier, per
  time-of-day (weekends, holidays).
- **No cross-currency reconciliation.** Provider settles in settlement currency, we book in
  settlement currency, but the auditor may reconcile in accounting currency (a third one). The
  POC ignores this third leg.

---

## Cross-cutting technical themes

### Money and math
- `BigDecimal` everywhere; `double` **nowhere**. `HALF_EVEN` rounding for bankers.
- `Money` is a value object with currency-aware scale. Cross-currency ops throw.
- `Currency` enum carries scale — VND is 0, USD is 2, JPY is 0.

### Idempotency
- Present in three POCs (payment, refund, recon). Same shape every time: DB `UNIQUE` constraint +
  fingerprint check + response replay.
- Idempotency is **the** load-bearing invariant. Every write path that a client can retry has one.

### State machines
- `Payment` and `Refund` both have explicit `canTransition(from, to)` methods that reject illegal
  moves in code — not in workflow config. This makes bugs surface at the smallest scope.

### Append-only ledgers
- Wallet's `LedgerEntry` is immutable. Balance is a cache. Truth = sum of ledger.
- This is the foundation for double-entry bookkeeping and the reason recon works.

### Transactional isolation
- `@Transactional` used deliberately. `Propagation.REQUIRES_NEW` where the outer failure must not
  poison the inner claim (idempotency records).
- H2 in the POC. Oracle / Postgres for production — behaviors differ subtly on
  `SELECT ... FOR UPDATE` semantics and unique-constraint error timing (flush vs commit).

### What we deliberately skipped in the POC
- **Outbox / Kafka.** Covered by a separate `cdc-outbox-poc` in this workspace. Every write path
  above would emit a domain event in production.
- **Distributed locking.** Not needed at POC scale. See `CONSISTENCY.md` for what changes when
  you scale beyond one pod.
- **Auth, rate limiting, quotas.** Cross-cutting concerns, well-understood, orthogonal to the
  invariants under test.
- **Metrics and tracing.** Every service call would be instrumented in production; POC keeps focus
  on the invariants.
