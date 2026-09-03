# KYC Workflow POC

A demonstration of a **multi-step async KYC workflow** built on Java 21 + Spring Boot 3.4.
The POC exercises the patterns called out in the source idea (#27):

> **Companion docs:**
> - [ISSUE.md](ISSUE.md) — the concrete problem, scope, non-goals, acceptance.
> - [TECHNICAL.md](TECHNICAL.md) — the hard sub-problems, what we protect, solution shape, tech by responsibility, tech debt.
> - [CONSISTENCY.md](CONSISTENCY.md) — what breaks when you scale to N pods / VMs, and the three-move fix.
> - [flow.html](src/main/resources/static/flow.html) — interactive walkthrough (served at <http://localhost:8080/flow.html> once the app is running).

- **Explicit state machine** — every transition validated against an allowed-transition table; illegal events throw, they never silently corrupt state.
- **Async pipeline** — OCR and address verification run on a dedicated executor with simulated latency.
- **Per-step retry with exponential backoff + jitter** — each step has its own retry budget.
- **Dead letter queue** — when retries are exhausted the case lands in the DLQ with full failure context preserved. An admin can replay it.
- **Manual review queue** — cases that pass the automated steps are routed to a human reviewer who approves or rejects.
- **Document references, not bytes** — uploads return an S3-style reference; the workflow stores only the reference.
- **Audit log** — every transition is recorded with from-state, to-state, event, actor, and detail.

## Run

```bash
# from kyc-workflow-poc/
mvn spring-boot:run
```

Then open <http://localhost:8080>.

If `mvn` is not on your PATH, point IntelliJ / VS Code at the `pom.xml` and run the
`KycApplication` main class with **JDK 21**.

## What the UI gives you

| Tab | What it does |
|-----|--------------|
| **Customer** | Register a new applicant, then upload an ID document for them. Submitting kicks off the async pipeline. |
| **All cases** | Live list of every case with state pill, attempt counters, and detail modal. |
| **Review queue** | Cases stuck in `PENDING_MANUAL_REVIEW` with inline Approve / Reject buttons. |
| **DLQ** | Cases that exhausted retries. Click **Replay** to push them back into the pipeline with a fresh budget. |
| **State Machine** | The transition diagram, rendered live. |

The page polls `/api/kyc/stats` and friends every 2 s, so transitions appear automatically.

## API

| Endpoint | Notes |
|----------|-------|
| `POST /api/kyc/register` | `{ fullName, email, declaredAddress }` → returns case |
| `POST /api/kyc/{id}/upload` | `{ fileName, sizeBytes }` → uploads doc, fires pipeline |
| `POST /api/kyc/{id}/review` | `{ decision: APPROVE\|REJECT, reviewerNote, reviewer }` |
| `GET  /api/kyc` | All cases |
| `GET  /api/kyc/{id}` | One case (full audit log + results) |
| `GET  /api/kyc/queue/manual-review` | Pending-review cases only |
| `GET  /api/kyc/dlq` | DLQ entries |
| `POST /api/kyc/dlq/{dlqId}/replay` | Replay a DLQ entry |
| `GET  /api/kyc/stats` | Counters by state |

## State machine

Allowed transitions (drawn live in the **State Machine** tab):

```
REGISTERED                            --UPLOAD_ID-->            ID_UPLOADED
ID_UPLOADED                           --START_OCR-->            OCR_IN_PROGRESS
OCR_IN_PROGRESS                       --OCR_SUCCESS-->          OCR_COMPLETED
OCR_IN_PROGRESS                       --OCR_FAILURE-->          OCR_FAILED
OCR_FAILED                            --RETRY-->                OCR_IN_PROGRESS
OCR_FAILED                            --MOVE_TO_DLQ-->          DEAD_LETTER
OCR_COMPLETED                         --START_ADDRESS_VERIFY--> ADDRESS_VERIFICATION_IN_PROGRESS
ADDRESS_VERIFICATION_IN_PROGRESS      --ADDRESS_VERIFY_SUCCESS-> ADDRESS_VERIFIED
ADDRESS_VERIFICATION_IN_PROGRESS      --ADDRESS_VERIFY_FAILURE-> ADDRESS_VERIFICATION_FAILED
ADDRESS_VERIFICATION_FAILED           --RETRY-->                ADDRESS_VERIFICATION_IN_PROGRESS
ADDRESS_VERIFICATION_FAILED           --MOVE_TO_DLQ-->          DEAD_LETTER
ADDRESS_VERIFIED                      --SEND_TO_MANUAL_REVIEW-> PENDING_MANUAL_REVIEW
PENDING_MANUAL_REVIEW                 --MANUAL_APPROVE-->       APPROVED   (terminal)
PENDING_MANUAL_REVIEW                 --MANUAL_REJECT-->        REJECTED   (terminal)
DEAD_LETTER                           --REPLAY_FROM_DLQ-->      ID_UPLOADED
```

Anything not in this table is rejected by `KycStateMachine.fire()` with an
`IllegalTransitionException` (HTTP 409). That's the whole point — out-of-order
async callbacks can't corrupt the case.

## Tuning the demo

`application.yml`:

```yaml
kyc:
  ocr:
    failure-rate: 0.20      # 1-in-5 OCR calls fail
    max-retries: 3
  address:
    failure-rate: 0.15
    max-retries: 3
```

Raise the failure rates to see more cases land in the DLQ.
Lower `max-retries` to make the DLQ fill faster.

## A typical demo run

1. Open the **Customer** tab → click **Register applicant** → the form auto-fills the new case ID.
2. Click **Upload & start workflow**.
3. Switch to **All cases**. Watch the case march through `ID_UPLOADED → OCR_IN_PROGRESS → OCR_COMPLETED → ADDRESS_VERIFICATION_IN_PROGRESS → ADDRESS_VERIFIED → PENDING_MANUAL_REVIEW`.
4. Open the **Review Queue** tab → **Approve**.
5. Submit a few more — one will eventually fail OCR / address enough times to land in the **DLQ**. Click **Replay**.

## Project layout

```
src/main/java/com/poc/kyc/
├── KycApplication.java
├── config/AsyncConfig.java          # workflowExecutor bean
├── model/                            # KycCase, KycState, KycEvent, audit/results
├── statemachine/KycStateMachine.java # transition table + fire()
├── service/
│   ├── KycWorkflowService.java       # orchestrator
│   ├── OcrService.java               # mock external (latency + stochastic failure)
│   ├── AddressVerificationService.java
│   └── DocumentService.java
├── repository/KycCaseRepository.java # in-memory; swap for JPA
├── controller/
│   ├── KycController.java
│   └── GlobalExceptionHandler.java
└── ...

src/main/resources/static/            # production-ready single-page UI
├── index.html
├── style.css
└── app.js
```

## Production checklist (what would change if this went live)

| POC | Production |
|-----|------------|
| In-memory repository | JPA + Oracle (matches the `daccount` project conventions) with optimistic locking on state |
| Mock OCR / Address services | Real HTTP clients + circuit breakers (Resilience4j) |
| Thread-local executor | Workflow engine (Temporal / Camunda) for durable execution + crash recovery |
| Per-instance DLQ map | Kafka DLQ topic + replay tool |
| No auth | VN-ID JWT integration on the controllers |
| Polling UI | SSE or WebSocket push from the workflow events |
| Hard-coded retry budget | Per-tenant config, observability on the retry decisions |
