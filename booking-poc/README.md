# booking-poc

Three booking/reservation patterns in one Spring Boot 3.4 + Java 21 app, backed by H2 and an in-memory Caffeine cache. No infra to install — `mvn spring-boot:run` and open `http://localhost:8080/`.

> Companion docs: [`docs/TECHNICAL.md`](docs/TECHNICAL.md) for the *why* (key tech, trade-offs, tech debt, design system, error codes) and [`flows.html`](src/main/resources/static/flows.html) for state + sequence diagrams.

| Pattern | Endpoint root | Demo page |
|---|---|---|
| **17. Seat hold (hotel / flight)** | `/api/seats` | [`/seat.html`](src/main/resources/static/seat.html) |
| **18. Calendar conflict (Calendly)** | `/api/calendar` | [`/calendar.html`](src/main/resources/static/calendar.html) |
| **19. Overbooking (airline)** | `/api/overbooking` | [`/overbooking.html`](src/main/resources/static/overbooking.html) |

## Run

```bash
mvn spring-boot:run
```

Then open <http://localhost:8080/>. The H2 console is at <http://localhost:8080/h2> (URL `jdbc:h2:mem:bookingpoc`, user `sa`, no password).

---

## 17. Seat hold (Caffeine TTL + DB hard hold + scheduled sweep)

Mirrors the cart-hold / seat-hold pattern: a soft hold that survives a mobile drop, promoted to a hard hold when checkout starts, with a payment deadline and auto-release.

```
POST /api/seats/{id}/soft-hold      { customerId }              -> token + expiresAt (5 min)
POST /api/seats/{id}/hard-hold      { customerId, softToken }   -> holdId + paymentDeadline (default 120 s)
POST /api/seats/{id}/pay            { customerId, holdId }      -> 200
POST /api/seats/{id}/release/{hid}                              -> 200
```

Design points worth keeping:

- **Soft hold lives in Caffeine** (`SoftHoldStore`) using `asMap().putIfAbsent` — atomic check-and-set, same semantics as Redis `SETNX EX`. Two shoppers tapping the same seat at once → only one wins; the other gets `seat_held`. Cost is zero DB pressure.
- **Hard hold lives in the DB** with a `paymentDeadline` column. Promotion uses `SELECT … FOR UPDATE` on the seat row so a race during the soft → hard transition still produces one winner.
- **Scheduled sweeper** (`HardHoldSweeper`, every 30 s) flips expired `PENDING` holds back to `AVAILABLE`. Caffeine handles soft-hold TTL on its own.
- **Graceful fallback** baked into the API: every conflict returns a structured `code` (`seat_held`, `soft_hold_invalid`, `hold_expired`, `seat_unavailable`) the UI can map to a friendly message.

---

## 18. Calendar conflict (recurring rules + multi-timezone + pessimistic overlap)

Calendly-style booking on top of a single owner table, a recurring-rule table, and an exception table.

```
GET    /api/calendar/owners
GET    /api/calendar/owners/{owner}/slots?from=YYYY-MM-DD&to=YYYY-MM-DD&slotMinutes=30
POST   /api/calendar/owners/{owner}/bookings    { inviteeName, startInInviteeTz, durationMinutes, inviteeTimezone }
GET    /api/calendar/owners/{owner}/bookings?from=…&to=…
DELETE /api/calendar/bookings/{id}
```

Design points worth keeping:

- **Recurring rules are wall-clock** (`LocalTime` + `dayOfWeek`), not instants. That's the only way "9:00 every Monday" stays at 9:00 across a daylight-saving shift. Expansion to UTC happens in `AvailabilityService` using the owner's IANA zone.
- **Bookings are stored UTC** (`Instant`) with the invitee's IANA zone retained for display. The invitee can send their local time + zone; we convert to UTC for the conflict check.
- **Overlap check** is the classic `start_a < end_b AND end_a > start_b`, run inside a `PESSIMISTIC_WRITE` lock on the matching set. Two clients posting the same slot at the same millisecond serialize — one gets the booking, the other gets `slot_taken` with the colliding invitee's name.
- **Buffer minutes** are applied on both sides of the requested window before the overlap query — so a 10-min buffer turns a 30-min booking into a 50-min keep-out for collision purposes.
- **Exceptions** (`RecurrenceException`) blank out a single date from a recurring rule without touching the rule itself.

H2 doesn't ship a real `tstzrange` + `EXCLUDE` constraint, so the POC enforces overlap at the application layer + pessimistic lock. In production Postgres, the EXCLUDE-using-gist approach is even better — the DB itself rejects any overlap.

---

## 19. Overbooking (probabilistic sell + bumping engine + comp ledger)

Sells beyond capacity, then resolves overflow at boarding time.

```
GET  /api/overbooking/flights
GET  /api/overbooking/flights/{code}                     -> capacity, sell ceiling, E[show-ups], E[overflow]
GET  /api/overbooking/flights/{code}/bookings            -> confirmed passengers
POST /api/overbooking/flights/{code}/book                { passengerName, fareClass, volunteerWillingness }
POST /api/overbooking/flights/{code}/board               { noShowBookingIds: [...] }
```

Design points worth keeping:

- **Sell ceiling** = `capacity × (1 + overbookFactor)`. `noShowRate` is stored alongside as the empirical input the factor was tuned from; the state endpoint reports `E[show-ups] = confirmed × (1 − noShowRate)` and `E[overflow] = max(0, E[show-ups] − capacity)` so analytics has the right knobs.
- **Sell is locked** with `PESSIMISTIC_WRITE` on the `Flight` row so two concurrent sells of the last ticket can't both succeed.
- **Bumping engine** (`BumpingService`):
  1. Mark no-shows.
  2. **Volunteer pass** — sort by willingness desc, then by lowest fare class. Anyone with `willingness ≥ 30` accepts the voluntary bonus and is bumped first.
  3. **Involuntary pass** — sort remaining show-ups by *reverse* fare-rank (basic economy first, first-class last), then by creation-time desc (latest sales bumped first). Each pays the higher involuntary compensation.
  4. Surviving passengers flip to `BOARDED`.
- **Compensation ledger** lives on `FlightBooking.compensationPaid`. The boarding response totals it so you can compare against the extra revenue from selling those overbooked tickets — that's the trade-off the model is solving.

---

## Layout

```
booking-poc/
├── pom.xml
├── src/main/java/com/example/bookingpoc/
│   ├── BookingPocApplication.java
│   ├── common/                 (BookingException, GlobalExceptionHandler)
│   ├── seat/                   (POC 17)
│   │   ├── api/                — Controller + DTOs
│   │   ├── domain/             — Seat, HardHold + enums
│   │   ├── repo/               — JPA repos with @Lock
│   │   └── service/            — SoftHoldStore, SeatService, HardHoldSweeper
│   ├── calendar/               (POC 18)
│   │   ├── api/                — Controller + DTOs
│   │   ├── domain/             — Booking, RecurringRule, RecurrenceException, CalendarOwner
│   │   ├── repo/
│   │   └── service/            — AvailabilityService, CalendarService
│   └── overbooking/            (POC 19)
│       ├── api/                — Controller + DTOs
│       ├── domain/             — Flight, FlightBooking, FareClass
│       ├── repo/
│       └── service/            — OverbookingService, BumpingService
└── src/main/resources/
    ├── application.yml
    ├── data.sql
    └── static/                 — index.html + 3 demo pages + style.css + common.js
```

## Tuning knobs (`application.yml`)

```yaml
booking:
  seat:
    soft-hold-ttl-seconds: 300       # Caffeine TTL on the soft hold
    sweep-interval-seconds: 30       # how often HardHoldSweeper runs
  overbooking:
    voluntary-bonus: 200             # paid to each voluntary bump
    involuntary-compensation: 800    # paid to each involuntary bump
```
