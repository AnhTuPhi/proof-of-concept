# CONSISTENCY.md — Scaling this system beyond one pod

Every POC in this suite runs as a **single process** with an in-memory H2 database. That is not a
compromise — it is a deliberate choice so that the invariants are visible in one place. This file
explains what changes, and what breaks, the moment you deploy on Kubernetes (multiple pods, HPA),
across VMs (multiple hosts), or across regions (multi-active).

The failure modes are ranked from **most likely to bite you first** to **most likely to bite you
last**.

---

## The one-line summary

Everything that currently works because it's in one JVM stops working when you have two JVMs.
The tools that put it back together are: **a shared database with real ACID**, **a distributed
cache (Redis) for hot idempotency and rate-limiter state**, **a message broker (Kafka) with
partition-keyed ordering**, and **a distributed lock service (Redis or ZooKeeper) for cases where
per-row DB locks are not enough**. The rest is discipline: sticky partitioning, idempotency
everywhere, no in-JVM state that matters.

---

## What each POC assumes today, and what breaks when you scale it

### 1. Idempotent payment — mostly safe, one hot-key hazard

**What still works.** The `UNIQUE` constraint on `idempotency_key` is enforced by the database,
not by the JVM. Two pods racing on the same key is exactly what the constraint arbitrates. The
loser reads the winner's row and replays.

**What breaks.**
- **Read-your-own-write on read replicas.** If the loser reads from a read replica that hasn't
  yet caught up, it sees "no such record" and tries to insert again — and only then hits the
  unique-constraint. That's fine, but slow. **Fix:** for idempotency reads, always go to the
  primary. Or add a small write-through Redis cache keyed on `idempotency_key` (5-minute TTL)
  that all pods consult first.
- **Hot key thundering herd.** 5,000 clients retrying the same key in the same second put 5,000
  concurrent inserts on the DB. **Fix:** short-lived Redis `SETNX` in front of the DB, TTL 30s,
  releases when the DB record is written. Serializes the herd before it hits the DB.
- **Idempotency record TTL.** In one pod / one DB, cleanup is a scheduled job. Across many pods,
  the janitor must be a **single instance** (K8s `CronJob` with `concurrencyPolicy: Forbid`, or a
  leader-elected scheduler like `ShedLock`) — otherwise N pods all try to delete the same batch.
- **Idempotency across regions.** Multi-active regions need a globally unique key. Do not let
  the `Idempotency-Key` be region-scoped by accident. If you write in region A and the retry
  lands in region B, the DB in region B has no row. **Fix:** either global DB (Aurora Global,
  Spanner), or eventual-consistent replication with client-visible region pinning (sticky-session
  cookie pins requests to the region of origin for the TTL of the key).

**Concrete K8s topology.**
```
[Ingress + HPA]───►[Payment pods × N]───►[Redis: SETNX idempotency lock]
                                     └───►[Postgres: idempotency_record UNIQUE]
                                     └───►[Kafka: payment.events partitioned by paymentId]

Janitor: CronJob {concurrencyPolicy: Forbid, schedule: every 15m}
```

### 2. Wallet concurrency — this is where scale hurts most

**What still works.**
- `PESSIMISTIC` (`SELECT ... FOR UPDATE`) works identically on any number of pods — the DB
  serializes.
- `OPTIMISTIC` (`@Version`) works — retries happen in the pod that observed the version conflict.
- `CONDITIONAL_UPDATE` (`UPDATE ... WHERE balance >= :amount`) works — the DB enforces the
  invariant atomically.

**What breaks.**
- **Hot wallet contention.** A single popular wallet under 10k RPS creates a lock queue on the
  DB row. Pessimistic: latency spikes to seconds. Optimistic: retry storms consume 100% CPU
  bouncing off `OptimisticLockException`. Conditional: many fail-and-return-error and never
  succeed. **Fix, in order of preference:**
  1. **Sharded balance** — replace one wallet row with N "bucket" rows that sum to the balance.
     Debits pick a random bucket; refunds land in any bucket. Kills contention at the cost of
     making balance reads a `SUM()`.
  2. **Queue in front of the wallet** — Kafka topic partitioned by `walletId`; a single consumer
     per partition applies debits serially. Trades latency for zero contention. This is how
     wallet systems at Uber/Grab scale actually run.
  3. **Distributed lock** — Redis `SETNX` with fencing token per `walletId`. Only for cases where
     you can't reshape the write path.
- **Read replicas lie about balance.** Any read that goes to a replica may show a stale balance.
  For balance display, that's fine (add a "as of Xs ago" hint). For the "can this debit succeed?"
  check inside the transaction, **never read from a replica** — always go to the primary, and
  always inside the same transaction as the write.
- **Ledger consistency drift.** In one JVM one DB, `sum(ledger) == balance` holds because both
  writes are in the same transaction. Under sharding, ledger rows for one wallet may live on N
  DB shards. **Fix:** keep the wallet balance and its ledger entries co-located on the same
  shard (shard by `walletId`), and add a per-shard scheduled reconciliation that alarms on drift.
- **Two K8s pods restart mid-transaction.** DB rolls back on connection loss. No harm — but
  the client sees an error and retries. Without an idempotency key at the wallet level, that
  retry may double-debit. **Fix:** wallet operations must also carry an idempotency key,
  short-TTL (60s) is fine.

**Choosing a strategy at scale.**
- Rare hot wallets, mostly cold traffic → `CONDITIONAL_UPDATE`.
- Hot wallets, few of them, latency-tolerant → `PESSIMISTIC`.
- Very hot wallets, latency-sensitive → **queue-based single-consumer per partition** (drop
  optimistic/conditional in favor of the Kafka approach).

### 3. Reconciliation — batch, so scaling is a different question

**What still works.** Recon is not on the hot path. It runs at end-of-day. The current
`ReconciliationService.runDailyReconciliation()` is single-threaded and idempotent
(`matched=false` filter).

**What breaks.**
- **Two pods run the recon job simultaneously.** Both start iterating unmatched rows. They race
  on `matched=true` writes. Depending on isolation level, one may double-book a break. **Fix:**
  the recon job is a **singleton** — K8s `CronJob` with `concurrencyPolicy: Forbid`, or use
  `ShedLock` (advisory DB lock) to gate the job across pods.
- **Recon takes 8 hours instead of 45 minutes as data grows.** The current implementation is
  `O(N)` app-side loops with per-row DB lookups. At 10M+ rows/day, you must:
  1. Rewrite recon as a **SQL set operation** — `FULL OUTER JOIN` on `providerRef` with `CASE`
     for break-type detection, emitted into a temp table, then batch-insert into `breaks`.
  2. Or move to a **batch framework** — Spark on parquet-exported ledgers is the industry standard
     above 100M rows/day.
- **Provider file arrives late / partial.** Current recon assumes both sides are complete. If the
  provider file lands during recon, half the internal txns look "missing at provider" spuriously.
  **Fix:** gate the recon on a **file-received sentinel** (an SQS message, a DB row, a file-lock)
  that the ingestion pipeline sets when the day is complete.
- **Recon report distribution.** In production the recon report goes to finance dashboards. The
  POC returns it in the HTTP response. Not a correctness issue, but the moment you scale the
  service you need a durable report store (S3, a report table) with retention.

**Concrete K8s topology.**
```
CronJob {schedule: "0 3 * * *", concurrencyPolicy: Forbid}
  ↓
Recon job pod (singleton) ── acquires ShedLock ── runs SQL FULL OUTER JOIN
  ↓                                                              ↓
  writes breaks to DB                                writes report to S3
  ↓
  emits `recon.finished` to Kafka for downstream dashboards
```

### 4. Refund flow — safe, with one caveat

**What still works.**
- Refund idempotency: same shape as payment, DB `UNIQUE` on `idempotency_key` arbitrates.
- Refund state machine: enforced in code, per-row transaction.
- Partial refund cap: enforced by `remainingRefundable` inside the transaction.

**What breaks.**
- **Two concurrent partial refunds for the same payment.** Both read `remainingRefundable = ₫100`,
  both pass validation, both write refund rows of ₫60. Sum = ₫120 > ₫100. **This is a bug even
  in one pod** — the fix is a **row lock on the `OriginalPayment`** during refund
  (`@Lock(PESSIMISTIC_WRITE)`) or a `CONDITIONAL_UPDATE` style approach
  (`UPDATE original_payment SET refunded_amount = refunded_amount + :n WHERE id = ? AND paid_amount - refunded_amount >= :n`).
  The POC deliberately ships without this because it violates the "one invariant per POC" rule —
  see `wallet-concurrency-poc` for the pattern. In production, apply that pattern here too.
- **Async settlement across pods.** Real refunds are `PROCESSING` for hours/days awaiting provider
  callback. The pod that started the refund may be gone by the time the webhook arrives. **Fix:**
  the webhook consumer is stateless; it looks up the refund by ID and walks the state machine.
  The pod that started the refund does **not** hold in-memory state.
- **Store-credit fallback across a shared store-credit balance.** The store-credit balance is now
  a shared resource, subject to the same wallet-concurrency invariants as any other wallet.
  Reuse the wallet POC's `CONDITIONAL_UPDATE` here.

### 5. Multi-currency — mostly safe, one clock hazard

**What still works.**
- Quote TTL: expressed in absolute timestamps, not durations. Any pod can check `now >= expiresAt`.
- Locked rate on payment: stored on the row, read by any pod for refund.
- FX P&L: append-only, no concurrency invariant beyond "no duplicate entries per payment+kind"
  (add a `UNIQUE (payment_id, kind)` if you need it).

**What breaks.**
- **Clock skew across pods.** Pod A creates a quote with `expiresAt = 15:00`. Pod B (clock 30s
  fast) rejects a valid payment at 14:59:31 real-time. Pod C (clock 30s slow) accepts a payment
  at 15:00:29 real-time. **Fix:** DB-generated timestamps for both quote creation and payment
  check (`DB.CURRENT_TIMESTAMP` in the WHERE clause). Do not use the pod's `Instant.now()` for
  the expiry gate.
- **Rate provider fan-out.** If every pod queries the market rate on every quote, you DDoS the
  market data feed. **Fix:** rate provider is a Redis-cached read-through with a 250ms TTL
  (or push-based updates via Kafka topic `fx.rates`).
- **Race between quote creation and provider outage.** Pod A creates a quote using the last known
  rate; the provider went dark 2s ago. Pod A doesn't know. **Fix:** the rate cache carries a
  `stalenessMillis` field; if > threshold, refuse to quote and force the client into a "please
  try again" path. Never quote against a stale rate silently.
- **Refund across regions with different accounting currencies.** If the accounting currency
  differs by region, the FX P&L entry needs to know which region's books it belongs to. **Fix:**
  add a `bookingEntity` column and route by region.

---

## The scale-up checklist (do these in order)

Before you deploy any of these POCs beyond one pod, you must have at least the first four items
in place. Items 5–8 come later as load grows.

**1. Real ACID database.** Postgres or Oracle. **Not** H2. Verify: `SELECT ... FOR UPDATE`,
`UNIQUE` constraint timing (immediate vs deferred), read-committed isolation as the default.

**2. Idempotency has a global backing store.** Every retry-safe endpoint has an
`Idempotency-Key` header, a `UNIQUE` constraint on the DB, and a Redis short-TTL front-cache to
absorb thundering herds. Cleanup is a **singleton** scheduled job with lease-based coordination
(`ShedLock`, K8s lease, or Redis lock).

**3. All financial writes are in a transaction that spans exactly the invariant.** Wallet debit +
ledger entry: same transaction. Refund insert + `refundedAmount` update: same transaction. FX
payment + P&L entry: same transaction. No cross-transaction "and also do X" — that must be an
outbox event.

**4. No in-JVM state that matters.** No in-memory rate cache without a shared fallback. No
in-memory "currently processing" set (use a DB row with status). No in-memory session (use Redis
or a JWT). Any pod can serve any request; any pod can die without waking a human.

**5. Kafka with keyed partitioning.** For every domain aggregate that can experience contention
(wallet, order, refund): a Kafka topic where messages are keyed by the aggregate ID. Downstream
consumers get **ordering guarantees per key**. This is how you move wallets from "DB is the
serialization point" to "the partition is the serialization point" — and lets you scale to
10x the throughput.

**6. Read replicas, used carefully.** Reads for display: replica. Reads for decisions (the "can
this debit succeed?" read): primary, inside the write transaction. Route explicitly at the DAO
layer — do not let framework magic pick.

**7. Cross-region strategy.** Choose one and commit:
   - **Single-region primary, DR replica.** Simple. Loses some in-flight on failover.
   - **Global-database (Aurora Global, Spanner).** Idempotency + row locks work naturally.
     Expensive.
   - **Region-partitioned traffic** (customer sticks to a region). Simplest at high scale;
     requires care at cross-region money transfer.

**8. Observability that lets you find the invariant violation.** For every invariant in
`ISSUE.md`, one metric or one alarm:
   - `wallet_balance_negative_total` — should be always 0.
   - `idempotency_key_collision_different_body_total` — signals a client bug or replay attack.
   - `refund_overshoot_attempted_total` — signals a race we didn't cover.
   - `fx_stale_rate_quote_total` — rate provider health.
   - `recon_break_open_total` per break type — daily trend.

---

## Concrete K8s manifest sketch (one POC as example)

Wallet service at scale:

```yaml
# HPA scales pods based on CPU AND custom metric (Kafka consumer lag)
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  minReplicas: 3
  maxReplicas: 30
  metrics:
    - type: Resource
      resource: {name: cpu, target: {type: Utilization, averageUtilization: 60}}
    - type: External
      external:
        metric: {name: kafka_consumergroup_lag}
        target: {type: AverageValue, averageValue: "100"}

---
# Deployment with anti-affinity so pods don't co-locate on a failing node
apiVersion: apps/v1
kind: Deployment
spec:
  template:
    spec:
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: ScheduleAnyway
      containers:
        - name: wallet
          env:
            - name: SPRING_DATASOURCE_URL
              value: jdbc:postgresql://pg-primary/wallet
            - name: SPRING_DATASOURCE_READONLY_URL
              value: jdbc:postgresql://pg-replica/wallet
            - name: SPRING_REDIS_HOST
              value: redis-cluster
            - name: SPRING_KAFKA_BOOTSTRAP
              value: kafka-headless:9092
          readinessProbe:
            httpGet: {path: /actuator/health/readiness, port: 8080}
          lifecycle:
            preStop:
              exec: {command: ["sh", "-c", "sleep 15"]}  # drain in-flight

---
# The recon job — singleton
apiVersion: batch/v1
kind: CronJob
spec:
  schedule: "0 3 * * *"
  concurrencyPolicy: Forbid  # <-- critical: never two jobs at once
  successfulJobsHistoryLimit: 7
  failedJobsHistoryLimit: 3
  jobTemplate:
    spec:
      backoffLimit: 2
      template:
        spec:
          containers:
            - name: recon
              image: fintech/recon:latest
              env:
                - name: SHEDLOCK_ENABLED
                  value: "true"
```

---

## The rules of thumb

- **If a piece of state matters, it lives in the database, not the JVM.**
- **If a race can happen inside one pod, it can happen worse across pods.** Design for concurrency
  before you scale out.
- **The database is the arbiter.** Unique constraints, row locks, and conditional updates are the
  only three primitives you need for 90% of correctness at scale.
- **Kafka is for ordering, not for correctness.** Kafka does not replace a DB constraint; it
  turns a hot-row contention problem into a per-partition throughput problem.
- **Idempotency is not optional at scale.** Any endpoint that a client, retry queue, or webhook
  can hit twice must be idempotent.
- **Singletons need explicit coordination.** Recon jobs, cleanup jobs, warmers, cache prefillers
  — one instance at a time, enforced by lease or DB lock, not by hope.

If you follow these rules, this suite of POCs scales cleanly from one pod on a laptop to a
regional deployment with tens of pods. If you break these rules, the invariants in `ISSUE.md`
break silently — and you find out from the finance team, not from a test.
