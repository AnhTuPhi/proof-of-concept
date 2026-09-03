# CONSISTENCY — Scaling the KYC Workflow

> Companion to [TECHNICAL.md](TECHNICAL.md). The POC runs in a single JVM. This document walks through what breaks when you deploy multiple replicas (k8s `replicas: N`, or several VMs behind a load balancer) and what has to change to keep the invariants from ISSUE.md #1–#7 intact.

---

## TL;DR

The POC uses three mechanisms that only work inside one JVM:

1. **`ConcurrentHashMap` for cases and DLQ** — visible only to the pod that wrote them.
2. **`synchronized (kycCase)` for atomic state-change + audit** — a JVM-local monitor.
3. **`ScheduledExecutorService` for retries** — dies with the pod.

Scale to N pods and you get N independent islands: register a case on pod A, poll on pod B → "not found". Two pods handling a duplicate webhook for the same case → both fire the transition, both win, audit log diverges. A pod restart mid-retry → the retry is silently lost.

Fixing this is a three-move sequence, in this order:

1. **Externalize state** (shared DB) — so any pod can see any case.
2. **Externalize the lock** (optimistic version on the row, or a partition-owner model) — so only one pod at a time can advance a given case.
3. **Externalize the scheduler** (durable timer) — so a retry survives a pod dying.

Skipping the ordering (e.g. sharing state but not the lock) is the classic way to ship a subtly broken workflow.

---

## 1. What actually breaks when you set `replicas: 2`

### 1.1 State is invisible across pods

`KycCaseRepository` is a `ConcurrentHashMap<String, KycCase>`. The map lives in the pod's heap. `POST /api/kyc/register` on pod-A puts the case in pod-A's map; a follow-up `GET /api/kyc/{id}` load-balanced to pod-B returns 404. Same for the DLQ — the review queue you see depends on which pod your browser landed on.

**Symptom in a demo.** Half your requests 404. Case counts on `/api/kyc/stats` differ every time you refresh.

### 1.2 The `synchronized` guard no longer guards

`KycStateMachine.fire()` synchronizes on the `KycCase` instance. Two pods each have their *own* copy of the case (once we do share state via a DB) — the JVM monitor on pod-A does not exclude pod-B. Two callbacks racing across pods can:

- both read `state = OCR_IN_PROGRESS`,
- both compute `next = OCR_COMPLETED`,
- both write, both append an audit entry.

Now the audit log has two `OCR_SUCCESS` entries for one attempt, and any downstream step (`submitAddressAttempt`) is enqueued twice.

**Why it matters.** The transition table's guarantee ("no illegal transition can succeed") is preserved — both writes are legal. But the invariant "one transition per real event" is not, and that is what audits, metrics, and downstream side-effects depend on.

### 1.3 Retries evaporate on pod death

`retryScheduler.schedule(runnable, 2000, MS)` holds the runnable in the pod's heap. Pod is evicted (rolling deploy, node drain, OOM kill) 500 ms in — the runnable is gone. The case sits in `OCR_FAILED` forever; the state machine will happily accept a `RETRY` event if someone fires one, but nobody ever will.

**Symptom.** After every rolling deploy, a handful of cases silently stall in `OCR_FAILED` / `ADDRESS_VERIFICATION_FAILED`.

### 1.4 In-flight work has no owner

A pod picks up a case, starts OCR (3 s call), pod is killed. There is no ledger saying "pod-A was working on case X since T", so no other pod knows to retry it. The case sits in `OCR_IN_PROGRESS` with no active work.

### 1.5 UI polling multiplies noise

Every pod is asked `/api/kyc/stats` by every open browser tab every 2 s. With N pods and M tabs that is N × M polls per interval. Not a correctness issue, but it is the first thing that shows up in the metrics graph.

---

## 2. The fix, in order

### Move 1 — Externalize state (shared DB)

Swap `KycCaseRepository` for a JPA repository against Oracle (matches the `daccount` project convention). Case, audit entries, DLQ entries all become tables.

Minimum schema shape:

```sql
kyc_case         (id PK, state, updated_at, version, full_name, email, ...)
kyc_audit        (id PK, case_id FK, from_state, to_state, event, actor, detail, ts)
kyc_dlq          (id PK, case_id FK, failed_at_state, step, last_error, attempts, replayed, created_at)
kyc_document     (id PK, case_id FK, s3_ref, type, size_bytes, uploaded_at)
```

`version BIGINT` is the JPA `@Version` column — the CAS token for move 2.

This alone fixes 1.1 (state visibility) and gives us the substrate for the other moves.

### Move 2 — Externalize the lock

There are three viable strategies. Pick one; do not mix.

#### 2a. Optimistic CAS on `version` (default recommendation)

Load the case with its `version`, compute the transition, write back with `UPDATE ... SET state = ?, version = version + 1 WHERE id = ? AND version = ?`. If the update affects 0 rows, another writer beat us — re-read, decide whether the event is still legal in the new state, and either fire again or drop the event.

- **Pros.** No locks held across I/O. Scales linearly with pod count. Cheap.
- **Cons.** The service code has to handle the retry loop and the "legal in the new state?" check. Retryable operations must be idempotent.

Cost per transition: 1 SELECT + 1 conditional UPDATE + 1 INSERT (audit). All in one transaction.

#### 2b. Pessimistic row lock

`SELECT ... FOR UPDATE` on `kyc_case` inside a transaction, then fire + insert audit + commit. The DB serializes concurrent writers for the row.

- **Pros.** Simplest mental model — the code looks almost identical to the POC's `synchronized`.
- **Cons.** Holds a DB lock across the transition. If the transition triggers an outbound call (it should not, but people do it), the lock is held across network I/O. Under load you queue on the row lock.

#### 2c. Partitioned single-writer (Kafka-style)

Hash `case_id` to a partition; each partition has exactly one owner pod (via a coordinator — Kafka consumer group, ZooKeeper lease, or a `case_owner` table with heartbeats). All writes for a case go through its owner.

- **Pros.** No cross-pod locking needed at all — the owner has exclusive access, so the POC's `synchronized (kycCase)` semantics *actually work* on that owner.
- **Cons.** Introduces a coordinator. Rebalance during a rolling deploy is a real event to design for. Overkill until traffic justifies it.

**Recommendation for most teams: 2a.** It is the smallest change from the POC and scales cleanly. Move to 2c only if you find yourself bottlenecked on DB CAS retries.

### Move 3 — Externalize the scheduler

The retry scheduler and the "who owns an in-flight step" question are the same problem: a piece of work that must happen, whether or not the pod that scheduled it is still alive. Two shapes:

#### 3a. DB-polled timer table

```sql
kyc_scheduled_work (
  id PK, case_id, step, run_at, attempt, claimed_by, claimed_until, done
)
```

Each pod runs `SELECT ... WHERE run_at <= now() AND claimed_until < now() AND NOT done LIMIT k FOR UPDATE SKIP LOCKED`, marks rows as claimed for a lease window (e.g. 30 s), does the work, marks done. Lease expires → another pod picks it up. `SKIP LOCKED` is the key — it makes the poll wait-free across pods.

- **Pros.** No new infra. Straightforward to reason about. Survives pod death (lease expires).
- **Cons.** Poll latency (usually 1 s) becomes the retry granularity floor. Not a problem here — backoffs start at 500 ms and grow.

#### 3b. Durable workflow engine (Temporal / Camunda / Quartz w/ JDBC)

Hand the whole orchestration off to an engine designed for this. Retries, timers, and pod-death-survival are the engine's problem.

- **Pros.** Ships with observability, replay, versioning, timers.
- **Cons.** Big dependency. Learning curve. Vendor lock-in.

**Recommendation for most teams: 3a until pain justifies 3b.** Temporal is the right answer once you have >1 workflow of comparable complexity.

### Move 4 (bonus) — Idempotency at the boundary

Once retries are durable, the OCR provider might see the *same* request twice (pod died after calling but before writing the result). Attach an `Idempotency-Key: {caseId}:{step}:{attempt}` header and either (a) require the provider to dedupe, or (b) record `(key → result)` in a local table and short-circuit on second call. Without this, "at-least-once retry" quietly becomes "sometimes-double-charge".

---

## 3. Consistency model at each step

With moves 1–3 applied, this is the consistency posture:

| Concern                                        | Guarantee                                                                                                     | Mechanism                                                          |
|------------------------------------------------|---------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| State + audit atomicity                        | Strong within a case                                                                                          | One DB transaction: CAS update + audit insert                      |
| One transition per real event                  | Strong within a case                                                                                          | `@Version` CAS + "still legal in new state?" recheck               |
| Cross-case ordering                            | None                                                                                                          | Cases are independent; no cross-case invariants exist              |
| Retry-after-crash                              | At-least-once                                                                                                 | DB-polled timer with `SKIP LOCKED` + lease                         |
| External call exactly-once                     | Not guaranteed — best-effort dedupe at boundary                                                               | Idempotency-Key header + provider dedupe                           |
| UI freshness                                   | Eventually consistent, ≤ 2 s (poll interval)                                                                  | Same as POC; SSE would tighten this                                |
| DLQ ordering                                   | None required                                                                                                 | Operators triage by attributes, not order                          |

Note the honest "at-least-once" on retries and the "not guaranteed" on external calls. This is the reality of distributed workflows — anyone claiming exactly-once is either lying or moving the problem somewhere else.

---

## 4. k8s specifics

### Deployment shape

- **Stateless pods.** All persistent state is in the DB. Rolling deploy is safe once move 3 is in.
- **Replicas.** Start at 2 for HA; scale on `workflow_queue_depth` or `retry_lag_seconds`, not CPU. CPU is a lagging indicator here.
- **Readiness vs. liveness.** Readiness gate on "DB reachable + workflowExecutor queue < 80%". Liveness only on JVM alive — do not tie liveness to downstream health or you cascade a provider outage into a pod restart loop.
- **PDB.** `maxUnavailable: 1` so rolling deploys don't take out the majority of workers holding leases.
- **`terminationGracePeriodSeconds: 60`.** Give in-flight steps time to finish and release leases cleanly. `preStop` hook stops the executors from accepting new work, then waits for the queue to drain.

### Session affinity

Not needed — the UI polls the API and the API is stateless. If you add SSE for push, either (a) run a small sticky-session ingress rule for the SSE path only, or (b) fan out events via Redis pub/sub / Kafka and let any pod push to any subscriber.

### Scaling the scheduler

The DB-polled timer (move 3a) scales trivially: every pod polls, `SKIP LOCKED` partitions the work. No coordinator needed. Watch `SELECT ... FOR UPDATE SKIP LOCKED` throughput on Oracle — with a decent index on `(run_at, done)` this is not a bottleneck until tens of thousands of pending timers.

### Failure drills to run before going live

1. **Kill a pod mid-OCR.** A different pod must pick the case up within the lease window (30–60 s).
2. **Kill a pod mid-retry-backoff.** The scheduled retry must still fire.
3. **Duplicate an inbound webhook.** Case must transition exactly once — the second webhook fires an illegal transition (409) or hits the idempotency dedupe.
4. **Partition the DB briefly.** Pods must fail readiness, LB drains them, no half-transitions land.

---

## 5. VM deployment (no k8s)

Same three moves. Differences:

- **No pod scheduler,** so a systemd unit + a health-check script + a load balancer (HAProxy / nginx) do the same job. Rolling deploy is manual — take one VM out of LB rotation, drain, deploy, re-add.
- **Lease TTL** should account for slower manual failover (60–120 s vs. k8s's ~30 s).
- **Graceful shutdown.** systemd `TimeoutStopSec=` mirrors the k8s `terminationGracePeriodSeconds`.
- **Scaling.** Add VMs behind the LB. Because pods are stateless post-move-3, this is boring — which is the goal.

The mistake to avoid on VMs is that they tend to live longer than pods, which lets you get away with cross-pod state issues for weeks before a coincident reboot exposes them. Test the failure drills anyway.

---

## 6. Migration path from the POC

If someone wanted to take this POC toward production without a rewrite:

1. **Add JPA.** Introduce entities for `KycCase`, `AuditEntry`, `DlqEntry`, `Document`. Keep the domain types; add `@Entity`, `@Version`. Replace `KycCaseRepository` with a Spring Data repository. **After this step: correctness is worse than the POC** (see 1.2). Do not deploy multi-replica.
2. **Add CAS on transitions.** In `KycStateMachine.fire()`, load the case fresh, compute next state, `UPDATE ... WHERE id = ? AND version = ?` inside a transaction, and if 0 rows: re-load and recheck legality. Same transaction inserts the audit row. **After this step: safe to run with N replicas.**
3. **Add durable timers.** Create `kyc_scheduled_work`. Replace `retryScheduler.schedule(...)` with an insert; add a poller bean using `SKIP LOCKED`. **After this step: retries survive pod death.**
4. **Add idempotency keys** at the OcrService / AddressVerificationService boundary.
5. **Add SSE** for UI freshness.
6. **Everything else in the [TECHNICAL.md](TECHNICAL.md) tech-debt list.**

Each step is a shippable increment. Do not batch them.
