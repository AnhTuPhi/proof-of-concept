# subscription-billing-pocs

Three runnable Spring Boot 3.4 / Java 21 POCs covering the hard parts of SaaS subscription billing. Each module ships with a polished HTML UI served on the same port as its REST API.

## Documentation map

| Doc | What it answers |
|-----|-----------------|
| **[ISSUE.md](ISSUE.md)** | Why these POCs exist — the business problem and the five money-safety invariants (I1–I5) every module protects. |
| **[TECHNICAL.md](TECHNICAL.md)** | Per-POC deep dive: the hard problem, what we protect, solution shape, key tech **by responsibility**, how each sub-problem is solved, and tech debt to acknowledge. |
| **[CONSISTENCY.md](CONSISTENCY.md)** | What must change to run this correctly behind a Kubernetes Deployment (N pods) or a VM fleet — exactly-once batch, DB-enforced idempotency, locks, outbox. |
| **[docs/overview.html](docs/overview.html)** | Self-contained interactive walkthrough of the three flows, the state machine, and the consistency model (with live proration + tier-pricing calculators). Open directly in a browser. |

| Module | Port | Topic | UI |
|--------|------|-------|----|
| `proration-engine-poc` | 8081 | Mid-cycle plan upgrade/downgrade/cancel, credit balance, no-cash-refund policy | http://localhost:8081/ |
| `dunning-poc` | 8082 | Failed-payment recovery: smart retry schedule, grace period, suspend, cancel | http://localhost:8082/ |
| `usage-billing-poc` | 8083 | Idempotent ingestion, daily rollups, tiered pricing, invoice + reconciliation | http://localhost:8083/ |

Each module has its own embedded H2 DB (`http://localhost:<port>/h2`) and seeds sample data on startup. The top-nav links between the three UIs.

## Build

```bash
mvn -f subscription-billing-pocs clean install        # build everything
mvn -f subscription-billing-pocs test                 # run unit + Spring tests (25 tests)
```

## Run a single module

```bash
mvn -f subscription-billing-pocs/proration-engine-poc spring-boot:run
mvn -f subscription-billing-pocs/dunning-poc          spring-boot:run
mvn -f subscription-billing-pocs/usage-billing-poc    spring-boot:run
```

Open the matching `localhost:<port>/` URL in any modern browser.

## UI overview

Each app's UI is a single-page dashboard with:
- **Sticky top nav** — cross-links to all three POCs
- **Cards** — clean white surfaces with subtle shadows; sectioned per responsibility
- **Tables** — striped, right-aligned monetary columns with tabular numerals
- **Status badges** — semantic colors (`ACTIVE` green, `PAST_DUE` amber, `SUSPENDED`/`CANCELED` red)
- **Toasts** — slide-in notifications for success/warning/error feedback
- **Money formatting** — locale-aware via `Intl.NumberFormat` (`$30.00`, color-coded by sign)
- **Time travel** — every action that takes a `today`/`asOf` date exposes a date picker so you can replay scenarios without touching the system clock

### What each page lets you do

| Page | Interactions |
|------|--------------|
| **Proration** | Browse plans, create a subscription, change plan (`IMMEDIATE_PRORATE` / `END_OF_CYCLE`), cancel mid-cycle, view all invoices with line items, look up credit balance |
| **Dunning** | Tick simulated time, attempt initial renewal, update payment method, drill into a subscription's renewal-attempt log and dunning-event timeline, browse the notification inbox |
| **Usage** | View pricing tiers per metric, ingest single events (with auto or custom `eventId`), bulk-ingest a day, run rollups, generate invoices, run reconciliation |

### Frontend architecture

```
src/main/resources/static/
├── index.html      # page structure
├── styles.css      # shared design system (~12 KB)
├── common.js       # Api / Fmt / Toast utilities (~5 KB)
└── app.js          # page controller — wires forms + tables
```

Vanilla HTML + CSS + ES2022 — no build step, no framework, no transpilation. Each app's static folder is independently complete so the bundle can ship as a single executable jar.

---

## 1) Proration engine (`proration-engine-poc`)

Stripe-style mid-cycle changes. **Always invoice — never directly charge.** A downgrade that produces negative net does not become a cash refund: surplus credit is banked on the customer's `CustomerCredit` row and consumed by the next positive invoice.

**Seeded plans:** `basic` ($10), `pro` ($30), `enterprise` ($100).

```bash
# 1. Create subscription for cust-1 on basic, today = 2026-06-01
curl -X POST http://localhost:8081/api/subscriptions \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"cust-1","planId":"basic","today":"2026-06-01"}'

# 2. Upgrade to pro mid-cycle on 2026-06-16 (half cycle remaining)
curl -X POST http://localhost:8081/api/subscriptions/1/change-plan \
  -H 'Content-Type: application/json' \
  -d '{"newPlanId":"pro","strategy":"IMMEDIATE_PRORATE","today":"2026-06-16"}'
# → $10 net: -$5 (unused basic) + $15 (prorated pro)

# 3. Inspect invoices and credit balance
curl http://localhost:8081/api/subscriptions/1/invoices
curl http://localhost:8081/api/customers/cust-1/credit-balance
```

**Strategies:** `IMMEDIATE_PRORATE` (Stripe default) or `END_OF_CYCLE` (no money moves today; plan switches at next renewal).

**Cancellation** mid-cycle issues a credit for unused days to the customer's balance.

---

## 2) Dunning (`dunning-poc`)

State machine: `ACTIVE → PAST_DUE → SUSPENDED → CANCELED`. Retry cadence is configurable in `application.yml`:

```yaml
dunning:
  retry-offsets-days: [3, 5, 7]    # smart retry: day 3, then day 5, then day 7
  grace-period-days: 2             # PAST_DUE → SUSPENDED after this
  suspension-window-days: 14       # SUSPENDED → CANCELED after this
```

**Seeded customers:** `cust-healthy` (working card), `cust-bad` (force-declined card).

```bash
# Attempt renewal — fails, schedules retry #1 at today + 3
curl -X POST 'http://localhost:8082/api/subscriptions/2/renew?today=2026-06-01'

# Advance time and tick the scheduler
curl -X POST 'http://localhost:8082/api/dunning/tick?today=2026-06-04'
curl -X POST 'http://localhost:8082/api/dunning/tick?today=2026-06-09'
curl -X POST 'http://localhost:8082/api/dunning/tick?today=2026-06-16'

# Customer updates card mid-flow — settles immediately and reactivates
curl -X POST 'http://localhost:8082/api/subscriptions/2/payment-method' \
  -H 'Content-Type: application/json' \
  -d '{"brand":"VISA","last4":"4242","expiresOn":"2031-12-31","today":"2026-06-10"}'

# Inspect timeline and sent notifications
curl http://localhost:8082/api/subscriptions/2/dunning-events
curl http://localhost:8082/api/subscriptions/2/attempts
curl http://localhost:8082/api/notifications
```

Notifications go through an in-memory `NotificationGateway` so tests can assert the email cadence (`retry_scheduled` → `final_warning` → `grace_period` → `service_suspended`).

---

## 3) Usage-based billing (`usage-billing-poc`)

Idempotent ingestion (`event_id` UNIQUE) → daily rollups → graduated tier pricing → invoice → reconciliation.

**Seeded pricing:**

| Metric | Tiers |
|--------|-------|
| `API_CALLS` | 0–100K free, 100K–1M @ $0.0010, 1M–10M @ $0.0005, 10M+ @ $0.0002 |
| `STORAGE_GB_HOURS` | flat $0.02 |

```bash
# Ingest events — replays of the same eventId are silently dropped
curl -X POST http://localhost:8083/api/usage/events \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"e-001","customerId":"cust-X","metric":"API_CALLS","quantity":"500000","occurredAt":"2026-06-05T12:00:00Z"}'

# Same eventId → duplicate=true, no change
curl -X POST http://localhost:8083/api/usage/events \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"e-001","customerId":"cust-X","metric":"API_CALLS","quantity":"500000","occurredAt":"2026-06-05T12:00:00Z"}'

# Roll up the day
curl -X POST 'http://localhost:8083/api/usage/rollup?customerId=cust-X&day=2026-06-05'

# Generate June invoice
curl -X POST 'http://localhost:8083/api/billing/invoices?customerId=cust-X&periodStart=2026-06-01&periodEnd=2026-07-01'

# Reconcile rollup vs raw — catches dropped or late-arriving events
curl 'http://localhost:8083/api/billing/reconcile?customerId=cust-X&periodStart=2026-06-01&periodEnd=2026-07-01'
```

**Key correctness guarantees:**
- Event ingestion is idempotent on `event_id` (DB-level UNIQUE + race-condition handling).
- Rollups are rebuildable — re-running for the same day overwrites and picks up late events.
- The invoice line items carry the per-tier `quantity` and `pricePerUnit`, so the customer dashboard can match the bill to ±0.0001.
- `ReconciliationService` compares `SUM(raw events)` vs `SUM(rollup totals)` per metric and flags any drift.

---

## Module layout

```
subscription-billing-pocs/
├── pom.xml                          # parent, Spring Boot 3.4.3, Java 21
├── common/                          # Money value type, BillingException
├── proration-engine-poc/            # mid-cycle plan changes
├── dunning-poc/                     # failed-payment recovery
└── usage-billing-poc/               # meter → rollup → tier-price → invoice → reconcile
```
