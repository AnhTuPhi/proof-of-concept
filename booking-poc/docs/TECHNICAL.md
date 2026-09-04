# booking-poc — Technical reference

Companion to [`README.md`](../README.md). The README explains *what to run*; this file explains *why each piece looks the way it does* — the hard problem behind every pattern, the concurrency primitive that solves it, the trade-offs we accepted, and the work still left for production.

Visual companion: [`flows.html`](../src/main/resources/static/flows.html) (Mermaid state + sequence diagrams).

---

## 1. The hard problem in each POC

| POC | Hard problem | Wrong-feeling answer | Right answer |
|---|---|---|---|
| **17 Seat hold** | Two shoppers tap "12A" in the same millisecond; one must lose cleanly while the other survives a mobile drop. | "Just update the DB row" — races + no TTL. | Two-stage lock: in-mem TTL soft hold (Caffeine `putIfAbsent`) → DB hard hold (`SELECT FOR UPDATE` + payment deadline + sweeper). |
| **18 Calendar conflict** | "9 AM every Monday" across DST + invitees in three timezones + buffer minutes + recurring rules with cancel-this-date exceptions. | Store local strings; compare them. Breaks at every timezone boundary. | Store UTC `Instant`; recurring rules in wall-clock + `ZoneId`; overlap = `start_a < end_b AND end_a > start_b` under `PESSIMISTIC_WRITE`. |
| **19 Overbooking** | Sell more tickets than seats to recover no-show revenue, then make the right people pay the price when too many show up. | Random kick / first-come-first-bumped. Hurts loyalty + expensive in comp. | Two-pass bump: volunteers first (highest willingness × cheapest fare), involuntary by reverse fare-rank + most-recent createdAt. Per-row comp ledger. |

---

## 2. Pattern 17 — Seat hold (TTL soft → DB hard → paid)

### 2.1 What we're protecting
A finite inventory (seats, hotel rooms) needs to be held during a multi-step checkout that may take minutes, may be abandoned, may run on a phone that drops onto LTE mid-flow. The naïve "update row to RESERVED, undo if no payment" path fails three ways at once:

1. Two shoppers race the `UPDATE` — both think they won (lost update).
2. Abandoned reservations never come back (memory leak in the DB).
3. The checkout client expects to reach payment in ≤ a few minutes, but the inventory lock has no idea.

### 2.2 Solution shape

```
[AVAILABLE] --softHold (Caffeine putIfAbsent, TTL 5m)--> [SOFT_HELD]
[SOFT_HELD] --TTL expires--> [AVAILABLE]
[SOFT_HELD] --hardHold (consume token + SELECT FOR UPDATE + deadline)--> [HARD_HELD]
[HARD_HELD] --pay--> [SOLD]
[HARD_HELD] --sweeper finds deadline < now--> [AVAILABLE]
```

### 2.3 Key tech, by responsibility

| Concern | Code | Why this primitive |
|---|---|---|
| Atomic check-and-set on a single key | [`SoftHoldStore.tryAcquire`](../src/main/java/com/example/bookingpoc/seat/service/SoftHoldStore.java) — `cache.asMap().putIfAbsent` | Equivalent to Redis `SET key val NX EX 300`. Two HTTP threads landing on the same seat in the same nanosecond → exactly one returns `null` (winner), the other returns the existing entry (loser). Zero DB pressure. |
| Auto-release of abandoned soft holds | Caffeine `expireAfterWrite(5 min)` | Lock-free, JVM-local. The TTL is the SLA: "the customer has 5 minutes to start checkout." |
| Atomic check-and-set on a DB row | [`SeatRepository.findByIdForUpdate`](../src/main/java/com/example/bookingpoc/seat/repo/SeatRepository.java) — `@Lock(PESSIMISTIC_WRITE)` | The soft → hard transition flips a column. Two concurrent promotions would otherwise both see `AVAILABLE` and both flip; `FOR UPDATE` serializes them. |
| Durable hold state | `HardHold` entity with `paymentDeadline` column | Survives JVM restart, unlike Caffeine. The deadline is the authoritative timer — no in-memory timer needed. |
| Cleanup of stale hard holds | [`HardHoldSweeper`](../src/main/java/com/example/bookingpoc/seat/service/HardHoldSweeper.java) — `@Scheduled(fixedDelayString)` | Runs every 30 s, queries `PENDING AND paymentDeadline < now()`, flips to `EXPIRED` and seat back to `AVAILABLE`. Sweeper isn't a clock — the column is. |

### 2.4 How it solves each subproblem

- **Race between shoppers:** `putIfAbsent` is the cheapest correct primitive — same semantics as Redis SETNX but in-process. No round-trip, no DB lock.
- **Mobile drop:** soft hold is in-memory + TTL. Drop → no further requests → TTL releases the seat. No retry storm, no operator cleanup.
- **Checkout abandonment after starting payment:** hard hold's `paymentDeadline` is a DB column read by the sweeper. The customer can close the tab; we still know when to release.
- **Crash recovery:** soft holds vanish (acceptable — customer was still browsing). Hard holds persist (necessary — customer was paying).

### 2.5 Tech debt to acknowledge

1. **Single-JVM only** — Caffeine doesn't span nodes. For multi-pod, swap `SoftHoldStore` for a Redis-backed implementation using `SET NX PX` + a Lua release script (to avoid releasing someone else's lock — the classic "two-phase commit" footgun in distributed locking).
2. **No idempotency keys** — a retried `POST /soft-hold` with the same `Idempotency-Key` header should return the same token rather than racing for a new hold. Stripe's pattern is the model.
3. **No metrics** — needs Micrometer counters: `seat_hold_wins`, `seat_hold_losses`, histogram on `hardHoldDurationSeconds`, gauge on `pending_hard_holds_count`. Without these, tuning the TTL is guesswork.
4. **No outbox** — daccount uses `EsOutboxService.enqueue` so async ES indexing has a safety net. Same idea should apply here: every state change emits an event for downstream (notification, analytics).
5. **TTL stampede** — all soft holds for a given second expire at the same instant. Add ±5% jitter to `expiresAt` so sweeps don't spike.
6. **Sweeper interval is global** — `30 s` is fine for the demo, but should be per-flight / per-resource if hold deadlines vary widely.
7. **Manual `releaseHold`** doesn't authenticate. Production needs the customer or a service token.

---

## 3. Pattern 18 — Calendar conflict (recurring rules + multi-timezone + pessimistic overlap)

### 3.1 What we're protecting

A finite resource that's the *owner's time*. Three traps converge:

1. **Range overlap is not range equality.** Two 30-min meetings can collide by overlapping for 1 minute. Equality checks miss this.
2. **Timezones lie.** "9 AM every Monday" expressed as a UTC offset is *wrong* on the day DST shifts. The rule must be wall-clock, the storage must be UTC.
3. **Recurring rules need exceptions.** Single-instance cancellations cannot live as "set the recurring rule's `validUntil` = today" — that breaks tomorrow too.

### 3.2 Solution shape

```
RecurringRule (wall-clock LocalTime + dayOfWeek + IANA ZoneId)
  ├─ expand over [from, to] day-by-day in owner's ZoneId
  ├─ skip dates listed in RecurrenceException
  └─ subtract bookings (Booking.startUtc..endUtc) under the owner's bufferMinutes
       => emit open slots as (Instant, Instant)

Booking submission:
  invitee local time + invitee ZoneId
    → resolve to (startUtc, endUtc)
    → widen by owner.bufferMinutes on both sides
    → findOverlappingForUpdate (PESSIMISTIC_WRITE)
       overlap? throw 409 slot_taken
       empty?   INSERT and 200
```

### 3.3 Key tech, by responsibility

| Concern | Code | Why this primitive |
|---|---|---|
| DST-correct recurring availability | [`RecurringRule`](../src/main/java/com/example/bookingpoc/calendar/domain/RecurringRule.java) stores `LocalTime` + `dayOfWeek` (NOT instant or offset) | A `LocalTime` resolved through `ZoneId` lets `ZonedDateTime` apply the correct UTC offset for the *specific date*. Storing an offset freezes that decision on the day you wrote the rule. |
| Range overlap detection | JPQL: `b.startUtc < :endUtc AND b.endUtc > :startUtc` | Classical interval overlap. O(1) per candidate row. |
| Serialization of concurrent bookers | [`BookingRepository.findOverlappingForUpdate`](../src/main/java/com/example/bookingpoc/calendar/repo/BookingRepository.java) — `@Lock(PESSIMISTIC_WRITE)` | Pessimistic lock on the matched range set: two clients calling `book(...)` for overlapping windows queue at the DB. Second one sees the first's INSERT after it commits. |
| Buffer minutes | [`CalendarService.book`](../src/main/java/com/example/bookingpoc/calendar/service/CalendarService.java) widens `[startUtc, endUtc]` to `[start − buf, end + buf]` before the overlap query | One-sided arithmetic on the query side keeps the table schema clean. |
| Single-date exceptions | [`RecurrenceException`](../src/main/java/com/example/bookingpoc/calendar/domain/RecurrenceException.java) keyed on `(ruleId, exceptionDate)` | Separate table avoids the "edit the recurring rule" antipattern; supports future kinds (SHIFT, ADD) without schema churn. |

### 3.4 How it solves each subproblem

- **DST shifts** (spring-forward Sunday): the rule "9 AM Monday" still expands to 9 AM Monday in the owner's zone. The UTC instant differs across DST boundaries by exactly one hour — correct.
- **Two clients picking the same Hanoi 14:00 slot from NYC and Tokyo:** both resolve to the same `Instant`. Their submissions race; pessimistic lock serializes; second one sees `slot_taken`.
- **Booking touches buffer of existing meeting:** widened query catches it. Configurable per owner.
- **Cancel just one Monday:** insert a `RecurrenceException` row. Original rule untouched.

### 3.5 Tech debt to acknowledge

1. **H2 can't enforce overlap at the DB layer.** Postgres can:
   ```sql
   ALTER TABLE calendar_bookings ADD CONSTRAINT no_overlap
   EXCLUDE USING gist (
     owner_id WITH =,
     tstzrange(start_utc, end_utc, '[)') WITH &&
   );
   ```
   This eliminates the application-level race entirely — the DB itself rejects an overlapping INSERT with a `23P01` exclusion violation. The current pessimistic-lock approach is the right shape *given* H2's constraints.
2. **No unbounded-range guard** — `slots(from=2026-01-01, to=2099-12-31)` would expand 73 years of rules. Need a cap or pagination.
3. **Only `kind=CANCEL` exceptions** — `SHIFT` and `ADD` aren't implemented.
4. **DST gap behavior is silent** — if invitee picks 02:30 on a spring-forward Sunday in `America/New_York`, `atZone()` shifts forward to 03:30 without error. Should be an explicit validation.
5. **Symmetric buffer only** — some use cases want different pre- and post-buffers (setup vs cleanup). Currently `bufferMinutes` is one number applied both sides.
6. **Invitee-side double-booking not detected** — if Alice books two different owners' Monday 9 AM slots simultaneously, both succeed. Production wants an invitee-scoped check too.
7. **No notification flow** — booking confirmed silently. Mail/SMS dispatch belongs in an outbox + worker, not inline.
8. **No reservation hold on slot view** — a hot owner could have two clients see the same slot as "open" and race the book request. Combining with the Pattern 17 soft-hold would close this.

---

## 4. Pattern 19 — Overbooking (probabilistic sell + bumping engine + comp ledger)

### 4.1 What we're protecting

The airline's revenue, against the no-show rate. Empirically ~10 % of confirmed passengers don't show up. If you sell exactly capacity, the average plane departs 90 % full. If you sell `capacity / (1 − noShowRate)`, the average plane departs full — but on the bad days, more people show up than fit, and you owe compensation.

The challenge is three-fold:

1. **Probabilistic sizing** — pick an `overbookFactor` that maximises `revenue_gained − expected_compensation`.
2. **Concurrent sell at the ceiling** — many parallel `POST /book` calls; only the first N up to the ceiling should succeed.
3. **Bumping policy** — when too many show up, choose who pays in a way that respects loyalty, legal exposure, and total compensation cost.

### 4.2 Solution shape

```
Sell:
  POST /book → SELECT FOR UPDATE Flight; count CONFIRMED;
               if count < ceiling → INSERT, else 409 sold_out

Board:
  POST /board {noShowBookingIds}
    1. mark no-shows
    2. while overflow > 0:
         Pass 1 (volunteer): take next by [willingness desc, fare-rank desc],
                              stop if willingness < 30, pay voluntary bonus
         Pass 2 (involuntary): take next by [fare-rank desc, createdAt desc],
                                pay involuntary compensation
    3. survivors → BOARDED
    4. return {boarded, voluntaryBumps, involuntaryBumps, compTotal, bumped[]}
```

### 4.3 Key tech, by responsibility

| Concern | Code | Why this primitive |
|---|---|---|
| Sell ceiling enforcement | [`OverbookingService.book`](../src/main/java/com/example/bookingpoc/overbooking/service/OverbookingService.java) — `findByCodeForUpdate` + `countByFlightCodeAndStatus` | Pessimistic lock on the flight row serializes "is there room?" decisions. Cheaper than counting all bookings under each thread. |
| Probabilistic accounting | `state(...)` returns `E[show-ups] = confirmed × (1 − noShowRate)` and `E[overflow]` | Caller can decide whether to sell more, or run a marketing push, before boarding. |
| Bumping order — voluntary | [`BumpingService.board`](../src/main/java/com/example/bookingpoc/overbooking/service/BumpingService.java) Pass 1 | `Comparator.comparingInt(volunteerWillingness).reversed().thenComparing(rank, reversed)` — picks high-willingness first; within ties, cheapest fare (cheapest to comp via voluntary bonus). |
| Bumping order — involuntary | Pass 2 | `Comparator.comparing(rank, reversed).thenComparing(createdAt, reversed)` — basic-economy first, latest sales first within a class. Cheapest legal exposure + protects loyal long-booked customers. |
| Compensation ledger | `FlightBooking.compensationPaid` (BigDecimal) | Persistent, per-row. Boarding response totals it. `SELECT SUM(compensationPaid)` answers "did overbooking pay off this quarter?" |

### 4.4 Why these sort keys

- **Volunteer pass uses willingness ↓ then cheap fare first** — a willing basic-economy passenger costs only the voluntary bonus to bump. Compare to a reluctant first-class passenger who would cost the involuntary compensation. Order saves cash and reputational drag.
- **Involuntary pass uses reverse fare-rank** — legal frameworks (US DOT, EU Reg 261/2004) and reputation align here: bumping a paid-up first-class passenger is the worst outcome. Basic-economy passenger bumped first is the industry norm.
- **Within a class, most recent first** — protects loyalty (long-booked customers feel less of a betrayal) and avoids bumping a passenger who already endured an earlier rebook.

### 4.5 Trade-off math

For a flight with capacity 10, factor 0.20 (ceiling 12), no-show rate 0.10:

- `E[show-ups]` at ceiling = `12 × (1 − 0.10) = 10.8`
- `E[overflow]` ≈ 0.8 on average
- Comp cost = `0.8 × $200 = $160` (assuming all volunteers — best case)
- Extra revenue = 2 tickets × avg fare. If avg ECONOMY fare = $400 → +$800.
- Net = +$640 per flight on average.

The factor should ramp until the marginal comp equals the marginal ticket revenue. That's the optimization the airline is making.

### 4.6 Tech debt to acknowledge

1. **`overbookFactor` is static per flight** — should be auto-tuned nightly from rolling 90-day no-show rate.
2. **Compensation is a flat constant** — real-world rules are tiered by delay length and flight distance (EU Reg 261/2004: €250 / €400 / €600). Model needs a `CompensationPolicy` strategy.
3. **No per-cabin capacity** — single pool. Real planes have FIRST/BUSINESS/ECONOMY caps; a sold-out economy cabin shouldn't get filled from an empty first-class.
4. **Bumping decisions aren't audited** — production needs an append-only `bumping_decisions` table with actor, reason, timestamp.
5. **No standby / auto-rebook** — bumped passengers should be re-queued onto the next flight automatically. Currently they're just `BUMPED_*` and forgotten.
6. **Gate-agent UI missing** — the response includes the bumped list, but a real airline has a live screen showing who's been bumped and why.
7. **`volunteerWillingness` is a single int** — in reality it varies by destination, by past frustration, and is gathered via "would you accept $X to take a later flight?" auctions at the gate. Out of scope, but the field is the seed.

---

## 5. Cross-cutting design choices

### 5.1 Concurrency primitives — pattern catalog

| Need | Primitive used | Suitable when |
|---|---|---|
| Atomic single-key check-and-set, in-process | `Caffeine.asMap().putIfAbsent` | Single JVM POC; ephemeral state. |
| Atomic single-key check-and-set, cross-node | (not in POC) Redis `SET NX PX` + Lua release | Multi-pod production. |
| Row-level write serialization | `@Lock(LockModeType.PESSIMISTIC_WRITE)` on `findBy…ForUpdate` | Conflicting writes against the same DB row. |
| Range overlap serialization | Same `@Lock(PESSIMISTIC_WRITE)` on the result-set of an overlap query | Calendar / booking ranges. |
| Optimistic conflict detection | `@Version Long version` on entity | Read-mostly entities where retries are acceptable. Used on `Seat`, `Flight`. |
| Stale lock cleanup, in-memory | Caffeine `expireAfterWrite(...)` | Soft inventory locks. |
| Stale lock cleanup, durable | `@Scheduled` sweeper reading `deadline < now()` | Hard inventory locks (must survive restart). |

The decision to use which primitive is in the comment above each method. Don't reach for `synchronized` — JPA entity instances are per-fetch new objects; the lock is on the wrong identity. (See the daccount CLAUDE.md, "Concurrency on Product writes" — same lesson.)

### 5.2 Money & quantity

Inherits the daccount discipline:

- **`BigDecimal` end-to-end** for fares, compensation, sell-ceiling calculations. Never `.longValue()` or `.doubleValue()` mid-arithmetic. (F14 ban.)
- **Explicit scale at boundaries**:
  - `setScale(2, HALF_UP)` for human-visible money.
  - `setScale(0, UP)` for "expected overflow seats" (rounding up is conservative — plan for the worst).
- **Null-defensive add**: `value == null ? BigDecimal.ZERO : value` if a column might be null.

### 5.3 Time

- **Persist as `Instant`** — UTC, no offset, no zone. The DB doesn't know what "Asia/Ho_Chi_Minh" means; it knows what milliseconds since the epoch mean.
- **Compute in `Instant`** — all arithmetic is duration-aware (`plus`, `minus`, `isBefore`, etc.).
- **Wall-clock for recurring rules** — `LocalTime` + `dayOfWeek` + IANA `ZoneId` referenced separately on the owning entity. *Never* a stored UTC offset, because DST.
- **Display via `ZonedDateTime`** — only at the API boundary, only for the person reading the screen. Internally everything stays UTC.
- **`hibernate.jdbc.time_zone: UTC`** in `application.yml` so the JDBC layer doesn't apply the JVM default zone on read/write.

### 5.4 Error model

- All thrown via `BookingException(HttpStatus, code, message)` — factories `notFound`, `badRequest`, `conflict`.
- All caught by `GlobalExceptionHandler` returning `{ timestamp, status, code, message }`.
- **`code` is a stable enum**, documented in Section 7. Clients switch on `code`, never on the human-readable `message`.

### 5.5 REST shape

- **Resource per module**: `/api/seats`, `/api/calendar`, `/api/overbooking`. No cross-module endpoints.
- **DTOs are `record`s** (Java 21). Value semantics, no mutation surface. Mapper methods on the DTO (`SeatView.from(seat)`) keep the domain entities clean.
- **POST verbs are imperative actions, not CRUD-ish**: `soft-hold`, `hard-hold`, `pay`, `release`, `board`, `book`. Reflects the state machine.
- **No 200-with-empty-body** unless the operation is genuinely fire-and-forget (`pay`, `release`).

### 5.6 Persistence

- **One module = one bounded context.** No cross-module JPA `@ManyToOne` relations. Modules talk via service calls (none in this POC, but the constraint matters when scaling out).
- **Repos return `Optional` or `List`. Never null.** Standard Spring Data.
- **`findByIdForUpdate` is explicit** — a reader that intends to write. `findById` is read-only. Naming carries the intent.
- **`@Version` for read-mostly rows**, pessimistic lock for hot-path writes.

---

## 6. Design system

Dark, enterprise-SaaS. Same token vocabulary as the user's DRM Admin work, applied minimally.

### 6.1 Color tokens (CSS custom properties in `style.css`)

| Token | Value | Meaning |
|---|---|---|
| `--bg` | `#0f1115` | Page background. |
| `--bg-card` | `#181b22` | Card / panel surface. |
| `--bg-card-2` | `#1f232c` | Nested surface (input, log). |
| `--fg` | `#e5e7eb` | Primary text. |
| `--fg-muted` | `#94a3b8` | Secondary text, labels. |
| `--accent` | `#6ee7b7` | Primary action / brand. |
| `--accent-2` | `#60a5fa` | Informational. |
| `--ok` | `#34d399` | Success / available / boarded. |
| `--warn` | `#fbbf24` | Pending / will-expire / bumped voluntary. |
| `--err` | `#f87171` | Sold / forbidden / bumped involuntary. |
| `--border` | `#2a2f3a` | 1 px hairlines. |

### 6.2 Components

| Component | Selector | Notes |
|---|---|---|
| Card | `.card` | 1 px border, 10 px radius, 18×20 padding. The atomic container. |
| Tabs | `nav.tabs > a` | 14×18 padding, active indicator = 2 px accent border-bottom. |
| Pill (state badge) | `.pill.{state}` | Uppercase, 11 px, color-coded by state. |
| Button | `button`, `.secondary`, `.warn`, `.danger` | Primary = accent bg. Secondary = border only. Warn/danger = solid warn/err. |
| Log | `.log` | Monospace, scrollable, line-level color (`.ok`, `.warn`, `.err`). |

### 6.3 Patterns

- **Status pill color is global** — green = available/success/boarded across every page. Yellow = pending. Red = terminal/forbidden. Never reuse a color for unrelated meanings.
- **One action per card.** Each lifecycle stage gets its own card so the user can read the transition (e.g. soft-hold → hard-hold → pay are three distinct cards on `seat.html`).
- **Activity log card last.** Always visible while the user is poking around — makes "what just happened?" answerable without F12.
- **Mermaid diagrams in [`flows.html`](../src/main/resources/static/flows.html) use the same token set** for visual consistency.

---

## 7. Stable API error codes

Clients should `switch (response.code)`, not `if (message.contains("..."))`.

### 7.1 Seat hold

| Code | Endpoint | Meaning |
|---|---|---|
| `seat_missing` | all `/api/seats/*` | Unknown seat id. |
| `seat_unavailable` | soft-hold, hard-hold | Seat is HARD_HELD or SOLD. |
| `seat_held` | soft-hold | Active soft hold by another customer. |
| `soft_hold_invalid` | hard-hold | Soft token expired or mismatched. |
| `hold_missing` | pay, release | Unknown hold id. |
| `hold_mismatch` | pay | Hold doesn't match seat + customer. |
| `hold_not_pending` | pay | Already paid / released / expired. |
| `hold_expired` | pay | Payment deadline passed. |

### 7.2 Calendar

| Code | Endpoint | Meaning |
|---|---|---|
| `owner_missing` | all `/api/calendar/*` | Unknown owner id. |
| `range_invalid` | slots | `from > to`. |
| `slot_invalid` | slots | `slotMinutes` outside (0, 240]. |
| `duration_invalid` | book | `durationMinutes` outside (0, 240]. |
| `tz_missing` | book | `inviteeTimezone` empty. |
| `tz_invalid` | book | Not a valid IANA timezone id. |
| `slot_taken` | book | Pessimistic overlap detected. |
| `booking_missing` | cancel | Unknown booking id. |

### 7.3 Overbooking

| Code | Endpoint | Meaning |
|---|---|---|
| `flight_missing` | all `/api/overbooking/*` | Unknown flight code. |
| `sold_out` | book | At sell ceiling. |

---

## 8. Production checklist

Sorted by impact, highest first:

1. **Redis-backed `SoftHoldStore`** — `SET NX PX` + Lua release. Multi-pod.
2. **`Idempotency-Key` header** on every mutating POST. Stripe pattern.
3. **Outbox table for state changes** — durable event stream for notifications, analytics, ES indexing. Mirror the daccount `EsOutboxService` shape.
4. **Micrometer metrics** — counters and histograms named above (Sections 2.5, 4.6).
5. **Audit log** — per-decision row for bumping, hold releases, manual overrides.
6. **Flyway / Liquibase migrations** — replace `ddl-auto=create-drop`. Required before non-throwaway data.
7. **Postgres `EXCLUDE` constraint** for calendar overlap (Section 3.5 #1).
8. **Auto-tune `overbookFactor`** from rolling no-show rate.
9. **Tiered compensation policy** for the bumping engine (EU Reg 261/2004 compliant).
10. **TTL jitter** to defuse stampede on the hot second.

---

## 9. Reading order for newcomers

1. [`README.md`](../README.md) — what to run.
2. This file (`TECHNICAL.md`) — the why.
3. [`flows.html`](../src/main/resources/static/flows.html) — the pictures.
4. [`seat/service/SeatService.java`](../src/main/java/com/example/bookingpoc/seat/service/SeatService.java) — canonical concurrent-write pattern.
5. [`calendar/service/AvailabilityService.java`](../src/main/java/com/example/bookingpoc/calendar/service/AvailabilityService.java) — rule-expansion algorithm.
6. [`overbooking/service/BumpingService.java`](../src/main/java/com/example/bookingpoc/overbooking/service/BumpingService.java) — business policy expressed as a sorted iteration.

Each of these is small enough to read in one sitting.
