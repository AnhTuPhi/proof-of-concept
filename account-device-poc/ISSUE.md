# ISSUE.md — Problems this POC suite exists to solve

This repository is a suite of three focused proof-of-concepts, each isolating one
**auth & access** problem that is easy to state, hard to get right, and expensive to
get wrong in a brokerage/financial context (VN Direct / DAccount).

Each POC is a standalone Spring Boot 3.4.3 / Java 21 module with its own port and its
own single-page demo, so the problem can be reproduced and watched end-to-end.

| # | POC | Module | Port | The problem in one line |
|---|-----|--------|------|-------------------------|
| 28 | Account Merge | `account-merge-poc` | 8081 | One human ended up with two accounts; merge them without losing data or breaking either login. |
| 29 | Multi-Device Sessions | `session-management-poc` | 8082 | User is logged in on 3 devices; a password change must kill every live session **now**, not in an hour. |
| 30 | Tiered Rate Limiting | `tiered-rate-limit-poc` | 8083 | Throttle traffic per plan tier across three independent dimensions, and tell the caller when to retry. |

---

## Issue 28 — Duplicate identity, one human

**Scenario.** A user signed up via Google two years ago, forgot about it, and just
signed up again with email + password. Now there are two `users` rows for the same
person. One has old trade orders and a watchlist; the other is the new "fresh" account
they are actively using.

**Why it's hard.**
- User data is spread across **N tables** (`users`, `auth_methods`, `trade_orders`,
  `watchlists`, `audit_events`) all keyed by `user_id`. A merge has to move rows in all
  of them atomically.
- You **cannot hard-delete** either account. Trade orders are financial records with
  audit/regulatory value, and the "losing" account's login method (Google) must keep
  working — the user will absolutely try to log in with it again.
- Fields **conflict**: two different display names, two different emails, two different
  phone numbers. Somebody has to decide which one survives, and that decision must be
  recorded.
- The operation must be **idempotent-ish and safe under retry** — a half-finished merge
  that moved orders but not the redirect pointer would strand data.

**What we are protecting:** financial records, audit trail, and the guarantee that
*every* auth method a real person owns continues to resolve to their single surviving
account.

---

## Issue 29 — Instant, global session revocation

**Scenario.** The same user is signed in on a phone, a laptop, and a tablet. They change
their password (or hit "log me out everywhere"). Every access token already issued to
every device must stop working immediately.

**Why it's hard.**
- Access tokens are **JWTs** — self-contained and stateless *by design*. That's what
  makes them fast (no DB hit per request), but it also means a signed token stays valid
  until it expires **even if you want it dead now**. Statelessness and instant revocation
  are in direct tension.
- Short-lived tokens alone don't solve it: a 60-second window is still 60 seconds of a
  compromised token working after a password change.
- **Refresh tokens** are long-lived and therefore a juicy theft target. If one is stolen,
  the thief can mint fresh access tokens indefinitely — you need to detect theft, not just
  hope it doesn't happen.
- "Log out this one device" and "log out everywhere" are different operations with
  different blast radii.

**What we are protecting:** the ability to *revoke trust instantly* on a stolen or
shared device, and to detect refresh-token theft rather than silently allowing it.

---

## Issue 30 — Fair throttling per plan, per dimension

**Scenario.** Free, Pro, and Enterprise users share the same API. We must throttle each
according to their plan, absorb short bursts, and never let one caller's abuse degrade
service for everyone else.

**Why it's hard.**
- A single global limit is wrong: it either starves paying users or lets free users soak
  the whole system.
- Throttling on **one dimension is not enough**. You need at least three, independently:
  - **per user** — so plan tiers mean something;
  - **per IP** — so one NAT'd corporate office or one bot swarm can't burn a shared pool;
  - **per endpoint** — so one hot/expensive endpoint can't lock a user out of everything else.
- The limiter must allow **bursts** (a page that fires 10 quotes at once is legitimate)
  while still enforcing a steady-state rate.
- When you say no, you must say **when to retry** (`429` + `Retry-After`), or clients will
  hammer you and make it worse.

**What we are protecting:** service availability and fairness — paying customers get their
quota, abusers get contained, and honest clients get actionable back-pressure instead of
opaque failures.

---

## Cross-cutting issue — Correctness when you scale out

All three POCs, as written, hold their critical state **in-process** (in-memory maps, an
in-memory H2 database). That is deliberate for a demo, but it hides the real production
problem: the moment you run **more than one pod/VM behind a load balancer**, each replica
has its own copy of "the truth."

- Rate-limit buckets per pod → running N pods silently multiplies every limit by N.
- Session/token-version state per pod → a "logout everywhere" on pod A is invisible to pod B.
- Merge redirects rely on a shared DB, but concurrent merges can still race.

This scaling/consistency problem is spelled out separately in
[CONSISTENCY.md](CONSISTENCY.md).
