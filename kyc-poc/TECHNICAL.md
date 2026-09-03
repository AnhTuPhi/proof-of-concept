# TECHNICAL — KYC Workflow POC

> Read [ISSUE.md](ISSUE.md) first for the problem framing. This document explains **the hard parts** of that problem, what we are protecting against, and the shape of the solution the POC settles on. It ends with the tech debt we are consciously carrying.

---

## 1. The hard problems, one at a time

Each sub-problem below is a place where a naive implementation quietly breaks in production. This is the substance the POC exists to demonstrate.

### 1.1 State corruption from concurrent async callbacks

**What breaks.** OCR is slow. While it runs, a retry can be scheduled by an earlier failure path, or a human can hit the "cancel" button, or a duplicate webhook can arrive. Two callbacks race to mutate `case.status`. The `updatedAt` field ends up matching one path, the `status` field matches the other, and the audit log is empty for the transition that "lost" the race.

**What we are protecting.**
- The invariant `(state, audit-log)` must always agree. A state change without a matching audit entry, or vice versa, is corruption.
- The invariant `next_state = table[current_state, event]` must hold. Nothing else may set `state`.

**Solution shape.** A single `KycStateMachine.fire(case, event, actor, detail)` method is the *only* legal writer of `state`. It looks up `(current, event)` in a hard-coded `Map<TransitionKey, KycState>`; missing keys throw `IllegalTransitionException` (→ HTTP 409). The state write **and** the audit-entry append happen inside a single `synchronized (kycCase)` block, so any losing callback either finds the state has moved on (and its `(from, event)` no longer maps) or waits its turn and appends cleanly.

### 1.2 Long, flaky, external I/O on the request thread

**What breaks.** Calling a 3-second OCR provider from a `@RestController` blocks Tomcat threads. Under load the connector queue fills, health checks miss their deadline, k8s restarts the pod, and the in-flight cases die with it.

**What we are protecting.**
- Request threads must return in ms.
- A slow provider must not consume all workflow capacity — different steps get isolated budgets so OCR stalling does not starve address verification.

**Solution shape.** Two dedicated `ThreadPoolTaskExecutor` beans (`workflowExecutor`, `retryExecutor`) with bounded queues. Controllers accept the request, fire the state transition into `ID_UPLOADED`, and return. The workflow service enqueues an `OcrService.extract(...)` call on `workflowExecutor` and returns. When the OCR future completes, the callback fires the next transition on the state machine and, on success, submits the next step. **No `@Async` self-invocation**, because that silently no-ops when called from inside the same class; the service submits `Runnable`s to the executor by name.

### 1.3 Retries — bounded, backed-off, resumable

**What breaks.**
- No retries → one flaky call = failed case.
- Unbounded retries → single stuck provider takes down the pool.
- Fixed retries → thundering herd when the provider comes back.

**What we are protecting.**
- The provider from a stampede on recovery.
- Our own executor capacity.
- Ourselves from silently retrying a case that is broken for a *business* reason (bad photo) forever.

**Solution shape.** Per-step retry budget (`kyc.ocr.max-retries`, `kyc.address.max-retries`) tracked on the case as `AtomicInteger ocrAttempts / addressAttempts`. On failure, `computeBackoff(attempt) = min(500 * 2^attempt + jitter[0,500), 10000)` — exponential with jitter, capped at 10s. Backoff is scheduled on a `ScheduledExecutorService`, not by `Thread.sleep` on a workflow thread. When `attempts >= maxRetries`, the case is moved to the DLQ instead of retried again.

### 1.4 Dead letter queue — a queue an operator can act on

**What breaks.** A `status = 'ERROR'` column nobody looks at. Or an alerts channel that people mute. Or logs.

**What we are protecting.**
- The right to give up on a case *safely* — with enough context to explain to a regulator or to a human why we did.
- The ability to replay a case after a fix, without inventing new state or losing the failure history.

**Solution shape.** When retries exhaust, we insert a `DlqEntry(caseId, failedAtState, stepName, lastError, attempts)` **and** fire `MOVE_TO_DLQ` on the state machine, so the case's audit log records the transition. The DLQ is a first-class REST resource; the UI lists it. Replay fires `REPLAY_FROM_DLQ`, resets attempt counters, re-enqueues from `ID_UPLOADED`, and marks the DLQ entry as replayed so operators cannot double-replay by accident.

### 1.5 Manual review without racing the pipeline

**What breaks.** Reviewer clicks Approve while a stray retry is still in flight. Both write to `state`. The retry wins because it acquires the DB row lock a millisecond later; the reviewer sees "Approved" in the UI but the case is `OCR_IN_PROGRESS` in the DB.

**What we are protecting.**
- The invariant that once a case is in `PENDING_MANUAL_REVIEW`, only `MANUAL_APPROVE` / `MANUAL_REJECT` may fire on it.

**Solution shape.** Same as (1.1) — the transition table itself is the enforcement. From `PENDING_MANUAL_REVIEW` no automated event is legal. A late retry callback that tries to fire `OCR_SUCCESS` on a case already in `PENDING_MANUAL_REVIEW` throws `IllegalTransitionException`, is logged, and does nothing.

### 1.6 Documents as references, not bytes

**What breaks.** Storing the ID card image bytes in the case aggregate means the "case" is now a multi-MB row, every list query is huge, and GDPR erasure requires rewriting rows in-place.

**What we are protecting.**
- Aggregate size and query cost.
- The ability to delete PII independently of the case metadata.

**Solution shape.** `DocumentService.uploadDocument(...)` returns a `Document` with an `s3Reference` (`s3://kyc-poc/{caseId}/{uuid}-{filename}`). The case stores the reference; the bytes live elsewhere (mocked in the POC).

### 1.7 "What happened to case X" — audit as a first-class artifact

**What breaks.** Support tickets require greping across pod logs, which have already rolled over. Compliance asks "who approved this and when" — the answer is buried.

**What we are protecting.**
- Traceability. Every state change must be attributable.

**Solution shape.** `AuditEntry(fromState, toState, event, actor, detail, timestamp)` appended inside the same `synchronized` block as the state write. Actor is a string (`"user"`, `"system"`, `"ocr-service"`, `"retry-scheduler"`, or the reviewer's identifier). Exposed on `GET /api/kyc/{id}` and rendered in the UI's detail modal.

---

## 2. Solution shape at a glance

```
┌───────────────┐    HTTP    ┌─────────────────────┐   fire()   ┌───────────────────┐
│  Browser UI   ├──────────▶ │  KycController      ├──────────▶ │  KycStateMachine  │
│  (SPA polls)  │            │  (returns fast)     │            │  transition table │
└───────────────┘            └──────┬──────────────┘            └────────┬──────────┘
                                    │                                    │
                                    │ enqueue                            │ writes
                                    ▼                                    ▼
                           ┌────────────────────┐              ┌────────────────────┐
                           │ workflowExecutor   │              │  KycCase           │
                           │ (bounded pool)     │              │  (state + audit)   │
                           └────────┬───────────┘              └────────────────────┘
                                    │
                                    ├─► OcrService (mock, latency + failure)
                                    ├─► AddressVerificationService
                                    └─► on failure:
                                        ├─ within budget → retryScheduler.schedule(backoff)
                                        └─ budget exhausted → DlqEntry + MOVE_TO_DLQ
```

Terminal states: `APPROVED`, `REJECTED`, `DEAD_LETTER`. Every other state has at least one outgoing edge.

---

## 3. Key tech, grouped by responsibility

| Responsibility                        | Tech                                                       | Where in the code                                              |
|---------------------------------------|------------------------------------------------------------|----------------------------------------------------------------|
| HTTP surface, JSON serialization      | Spring Boot 3.4, Spring MVC, Jackson                       | `controller/KycController.java`, `controller/dto/*`            |
| Central error handling                | `@RestControllerAdvice`                                    | `controller/GlobalExceptionHandler.java`                       |
| State transition enforcement          | Custom in-memory transition table + `synchronized` guard   | `statemachine/KycStateMachine.java`                            |
| Async orchestration                   | `ThreadPoolTaskExecutor` (bounded queue)                   | `config/AsyncConfig.java` → `workflowExecutor`                 |
| Retry scheduling                      | `ScheduledThreadPoolExecutor` + exponential backoff+jitter | `service/KycWorkflowService.computeBackoff`                    |
| Case + DLQ persistence (POC)          | `ConcurrentHashMap`                                        | `repository/KycCaseRepository.java`                            |
| Audit log                             | `Collections.synchronizedList(...)` on the case aggregate  | `model/KycCase.java`, `model/AuditEntry.java`                  |
| Document as reference                 | Mock S3 key generator                                      | `service/DocumentService.java`                                 |
| Configurable failure injection        | `application.yml` (`failure-rate`, `max-retries`, delays)  | `service/OcrService.java`, `AddressVerificationService.java`   |
| UI                                    | Vanilla HTML + JS SPA, 2 s polling                         | `src/main/resources/static/{index,style,app}.html/css/js`      |

Deliberately **not used**:
- Spring Statemachine — heavier than the problem, and the transition table is the entire contract.
- `@Async` — subject to self-invocation footgun; explicit executor submission is clearer.
- A message broker — introduces a component whose value only appears at multi-instance scale (see [CONSISTENCY.md](CONSISTENCY.md)).

---

## 4. Sub-problem → mechanism map (compressed)

| Sub-problem                                | Mechanism                                                                                                                | Why this specifically                                                                        |
|--------------------------------------------|--------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| Illegal transitions corrupt state          | Hard-coded `Map<(state, event), nextState>`; missing key throws                                                          | The transition table *is* the contract; making it explicit removes conditional branches      |
| Concurrent callbacks race                  | `synchronized (kycCase)` around read-state + fire + append-audit                                                         | State + audit must move as one atomic pair; case-scoped lock isolates unrelated cases        |
| Slow provider blocks HTTP threads          | Enqueue on `workflowExecutor`, return HTTP 202-shape response                                                            | Bounded, separately observable pool; controller stays cheap                                  |
| Retries stampede or never happen           | Per-step attempt counter + `500 * 2^n + jitter`, capped at 10s, capped at `max-retries`                                  | Standard AWS-style backoff; jitter avoids herd; cap prevents runaway                         |
| Retry sits on a workflow thread            | `retryScheduler.schedule(...)` on `ScheduledThreadPoolExecutor`                                                          | Workflow threads stay free for actual work                                                   |
| Nowhere to park exhausted cases            | `DlqEntry` + `MOVE_TO_DLQ` transition; UI has "Replay"                                                                   | Operators get a list + button; the case's audit shows the DLQ transition                     |
| Replay double-fires                        | `DlqEntry.replayed` flag flipped inside `replayFromDlq`                                                                  | Idempotency for the human-in-the-loop path                                                   |
| Reviewer racing with retry                 | `PENDING_MANUAL_REVIEW` has no outgoing automated edges                                                                  | The table forbids it — no runtime check needed                                               |
| Bytes bloat the aggregate                  | `DocumentService` returns a reference; case stores the string                                                            | Aggregate stays small; PII lives elsewhere                                                   |
| "What happened to case X"                  | Audit entry appended atomically per transition, exposed at `GET /api/kyc/{id}`                                           | Support answers land in one query                                                            |
| Client sees stale state                    | UI polls `/api/kyc/stats`, `/api/kyc`, `/api/kyc/queue/manual-review` every 2s                                           | Good enough for POC; SSE/WebSocket noted in prod checklist                                   |

---

## 5. Tech debt we are consciously carrying

Every one of these is a known limitation of the POC, not an oversight. They are called out here so a future maintainer does not treat the POC as a template to copy verbatim.

1. **State lives in a `ConcurrentHashMap`.** All cases die on restart. No horizontal scaling. → Swap for JPA + Oracle with `@Version` optimistic locking on `state`.
2. **`synchronized (kycCase)` is a JVM-local lock.** Two pods holding the "same" case (they don't in the POC because state is in-memory, but they would once we back it with a shared DB) would each think they hold the lock. → See [CONSISTENCY.md](CONSISTENCY.md) — this is the central scaling issue and requires either single-writer partitioning or DB-row-level pessimistic locking / optimistic-version CAS.
3. **Retry scheduler is a local `ScheduledExecutorService`.** If the pod dies mid-backoff, the retry never fires; the case is stuck in `OCR_FAILED` forever. → Durable scheduling (Quartz w/ JDBC store, Temporal, or DB-polled `next_attempt_at` column).
4. **DLQ is a `ConcurrentHashMap`.** Same fate on restart. → Kafka DLQ topic, or a `dlq_entries` table.
5. **No idempotency keys on outbound calls.** If we ever move retries to at-least-once delivery (broker-backed), the OCR provider might see the same request twice. → Attach a deterministic key per `(caseId, stepName, attempt)` and require the provider to dedupe, or track "we already got a success for this key" locally.
6. **Poison-message vs. transient failure is not distinguished.** A photo of a cat burns 3 OCR retries before landing in the DLQ. → Classify errors (`RETRYABLE`, `TERMINAL`, `POISON`) at the service boundary and skip retries for terminal ones.
7. **No authentication.** Anyone can register, upload, approve. → VN-ID JWT filter on the controllers.
8. **Polling UI.** Every browser tab pulls `/api/kyc/stats` every 2 s. Fine for demo, wasteful at scale. → SSE stream of state-change events.
9. **Mock OCR / address services.** Latency and failure are random; no notion of "the provider is down and we should short-circuit." → Real HTTP clients wrapped in Resilience4j circuit breaker + bulkhead.
10. **No metrics / traces.** `management.endpoints` exposes `health,info,metrics` but no custom counters for transitions per state, retry outcomes, DLQ depth. → Micrometer + a Grafana dashboard would be a small addition.
11. **Audit log lives on the aggregate.** Fine while the case is in one JVM, painful at scale (loading a case pulls every audit row it has ever had). → Separate `audit_entries` table keyed by `case_id`, loaded on demand.
12. **Reviewer identity is a free-text field.** No validation, no link to a user record. → Reviewer comes from the JWT once auth exists.
13. **`REPLAY_FROM_DLQ` always sends the case back to `ID_UPLOADED`.** In reality you might want to resume from `OCR_COMPLETED` if only address verification failed. → Encode the resume point in the `DlqEntry` and add per-resume-point transitions.

None of these are hidden; each has a corresponding line in the "Production checklist" in [README.md](README.md).
