# Auth & Access POC Suite

Three gnarly **auth & access** patterns, each shrunk to a single self-contained Spring Boot
module you can run and watch end-to-end. Java 21 / Spring Boot 3.4.3. Built in the context
of VN Direct's **DAccount** platform, but each POC is standalone.

| # | POC | Module | Port | Hard problem | Demo |
|---|-----|--------|------|--------------|------|
| 28 | **Account Merge** | `account-merge-poc` | 8081 | Merge two accounts belonging to one human across N tables — no data loss, both logins keep working. | `/account-merge.html` |
| 29 | **Multi-Device Sessions** | `session-management-poc` | 8082 | Kill every live JWT session instantly on password change; rotate refresh tokens and detect theft. | `/session.html` |
| 30 | **Tiered Rate Limiting** | `tiered-rate-limit-poc` | 8083 | Token-bucket throttling per plan tier across user / IP / endpoint, with `Retry-After`. | `/rate-limit.html` |

## Documentation

| Doc | What's in it |
|-----|--------------|
| [ISSUE.md](ISSUE.md) | The business problem each POC solves — what we're protecting and why it's hard. |
| [TECHNICAL.md](TECHNICAL.md) | Per-POC: solution shape, key tech by responsibility, how each sub-problem is solved, tech debt. |
| [CONSISTENCY.md](CONSISTENCY.md) | What breaks when you scale to N pods/VMs and how to fix it (Redis, row locking, k8s concerns). |
| [architecture.html](architecture.html) | **Visual explainer** — flow diagrams and key-tech breakdowns for all three POCs. Open in a browser via `file://`. |
| [index.html](index.html) | Launcher hub linking to each running demo. |

## Running the suite

All commands run from `D:\Claude\poc-demos`. Build the parent once, then start each module
in its own terminal:

```bash
mvn -q clean install -DskipTests
mvn -q -pl account-merge-poc       spring-boot:run   # http://localhost:8081/account-merge.html
mvn -q -pl session-management-poc  spring-boot:run   # http://localhost:8082/session.html
mvn -q -pl tiered-rate-limit-poc   spring-boot:run   # http://localhost:8083/rate-limit.html
```

Open [index.html](index.html) (works over `file://`) as a launcher, or hit any demo URL
directly. Each module serves its own single-page demo from `src/main/resources/static/`.

## Architecture at a glance

```
poc-demos-parent (pom)
├── account-merge-poc        H2 in-memory · JPA · @Transactional soft-merge + redirect tombstone
├── session-management-poc   jjwt HS256 · in-memory sessions · token-version revocation + refresh rotation
└── tiered-rate-limit-poc    lazy token-bucket per (user, IP, endpoint) · 429 + Retry-After
```

Each module is a standard 4-layer Spring Boot app: `web` (controllers) → `service`
(business logic) → `domain` (entities/records) → `repo` (account-merge only). State is held
**in-process** on purpose so the demos are zero-dependency; [CONSISTENCY.md](CONSISTENCY.md)
covers what to change before running more than one replica.

> ⚠️ **Demo-only shortcuts** (documented in TECHNICAL.md): in-memory stores, a toy password
> hash, hardcoded tiers, and a shared HMAC secret in config. None of these ship as-is.
