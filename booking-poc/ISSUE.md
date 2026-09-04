## Booking / reservation

### 17. Hotel/flight seat hold during checkout
**Scenario**: User picks seat 12A. Backend holds it. Pays in 5 minutes or hold releases.

**Why hard**: Concurrent users seeing same seat available. Mobile network drops. Time-zone of the hotel vs user.

**Patterns**: Reservation TTL with Redis + scheduled sweep, **soft hold → hard hold on payment init**, "your seat may not be available" graceful fallback.

**POC**: seat-reservation-poc — same pattern as cart hold but with fixed inventory.

### 18. Calendar conflict (Calendly pattern)
**Scenario**: Two people book same 30-min slot via Calendly at same second.

**Why hard**: Overlapping ranges (not exact equality), buffer time, timezone conversion bugs, recurring event exceptions.

**Patterns**: Database range type (Postgres tstzrange with EXCLUDE constraint), **always store UTC**, recurrence as rule + exception list.

**POC**: calendar-booking-poc — range exclusion, buffer, multi-timezone, recurrence.

### 19. Overbooking (airline pattern)
**Scenario**: Plane holds 180. Airline sells 195 tickets because ~10% no-show.

**Why hard**: Voluntary bumping incentives, involuntary bumping rules, compensation calc, choosing whom to bump.

**Patterns**: Probabilistic overbooking model, last-class-of-fare bumping first, **compensation cost vs lost revenue trade-off**.

**POC**: overbooking-poc — probabilistic model + bumping logic + compensation flow.