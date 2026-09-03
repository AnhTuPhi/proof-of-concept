# ISSUE — KYC Workflow POC

> Source ticket: idea #27 — "KYC onboarding is drifting; we need a workflow that survives real-world failures."

## 1. Problem statement

KYC onboarding is not a single request/response — it is a **multi-step, multi-actor, long-running** process that mixes synchronous user input with slow, flaky external calls (OCR, address verification, sanctions, manual review). In the current implementation these steps are stitched together with ad-hoc `@Async` methods, mutable status fields, and per-call `try/catch`.

The consequences we have observed or can predict:

1. **State corruption under concurrent async callbacks.** OCR completes while a retry has already been scheduled → the same case advances twice → the audit log is inconsistent with the status field. Nothing throws; the bad data is only noticed weeks later by ops.
2. **Silent data loss on external failure.** A transient 5xx from the OCR provider bubbles up as an unchecked exception. The `@Async` boundary swallows it. The case sits in `OCR_IN_PROGRESS` forever.
3. **No bounded retry policy.** Retries are either absent (one shot, then dead) or unbounded (hammer the flaky provider until it comes back — during which time the pool starves everything else).
4. **No place to park unrecoverable cases.** When retries do exist and are exhausted, the case is either lost, or written to a `status = 'ERROR'` row that nobody triages. There is no queue an operator can list, no replay button.
5. **Manual review is bolted on.** The reviewer's decision is written back to the same status column that the async pipeline is also writing to — race conditions between "reviewer approves" and "background job retries" are real.
6. **We store bytes we shouldn't.** ID card images end up base64-encoded in the DB row, blowing up backups and making GDPR erasure a story.
7. **We can't answer "what happened to case X."** No audit log tying (from-state, to-state, event, actor, detail) to each transition — support tickets require digging through application logs across 3 pods.

## 2. What this POC has to prove

A minimal but **honest** workflow substrate that removes each failure mode above. Not a real KYC — the mocked OCR/address services are fine. The point is the **shape** of the orchestration:

- Every state change goes through **one place** with a hard-coded transition table. Out-of-order events throw, they do not corrupt.
- Long steps run on a **dedicated executor**, not on Tomcat threads.
- Each step has an **explicit retry budget** with exponential backoff + jitter.
- Exhausted retries land in a **DLQ** that carries enough context to be replayed by an operator.
- Cases that pass automation go into a **human review queue** with clear approve/reject affordances.
- Documents are stored as **references** (S3 keys), never as bytes in the case.
- Every transition writes an **audit entry** — the case's history is a first-class artifact, not a log line.

## 3. Scope

**In scope for the POC:**
- Java 21 + Spring Boot 3.4 service on `:8080`
- Custom state machine (no Spring Statemachine) — small enough to read in one sitting
- Mock OCR + address verification with configurable latency and failure rates
- In-memory repository for case, DLQ, audit log
- Single-page UI to register, upload, watch cases, act on the review queue, replay the DLQ
- REST API mirroring the UI actions
- Production checklist calling out what would change on the way to prod

**Explicitly non-goals:**
- Real OCR / real address provider integration
- Persistence, HA, multi-instance coordination (see [CONSISTENCY.md](CONSISTENCY.md))
- AuthN/Z (no VN-ID JWT wiring)
- Any actual regulatory compliance claim

## 4. Acceptance

The POC is considered "done proving the shape" when:

- [x] A user can register + upload → case runs end-to-end without operator intervention on the happy path.
- [x] With `failure-rate > 0`, cases fail some steps, retry with backoff, and either succeed within the budget or land in the DLQ.
- [x] Replaying a DLQ entry resets the retry budget and pushes the case back through the pipeline.
- [x] Firing an event that is not in the transition table returns HTTP 409, never a partial state change.
- [x] The audit log for any case contains one entry per transition with actor + detail.
- [x] The UI reflects state changes within one polling interval (≤ 2 s) without page reload.

## 5. Risks the POC deliberately does not solve

These are called out here so a reader does not mistake the POC for a production design. Each has a home in [TECHNICAL.md](TECHNICAL.md) (tech debt section) or [CONSISTENCY.md](CONSISTENCY.md):

- In-memory state → **all cases lost on restart**, cannot scale beyond one JVM.
- `synchronized (kycCase)` guards state within one JVM only → **breaks under horizontal scaling**.
- Retry scheduler is a local `ScheduledExecutorService` → **retries lost if the pod dies mid-backoff**.
- No idempotency keys on external calls → duplicated work if we ever add at-least-once delivery.
- No poison-message detection separate from the retry budget — a case that is broken for a business reason (bad ID) burns the full retry budget before landing in the DLQ.
