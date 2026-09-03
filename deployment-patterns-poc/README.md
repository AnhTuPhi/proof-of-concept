# Deployment Patterns Demo

A small Spring Boot 3.3 + Java 21 POC that demonstrates four production
deployment patterns in a single runnable app:

| Pattern | Where to look |
|---|---|
| **Blue-Green / Canary** traffic routing | [`canary/TrafficRouter.java`](src/main/java/com/demo/deployment/canary/TrafficRouter.java) |
| **Feature flag** (deploy ≠ release) | [`featureflag/FeatureFlagService.java`](src/main/java/com/demo/deployment/featureflag/FeatureFlagService.java) |
| **Graceful shutdown** with request draining | [`shutdown/GracefulShutdownListener.java`](src/main/java/com/demo/deployment/shutdown/GracefulShutdownListener.java) |
| **Tiered health checks** (liveness / readiness / startup) | [`health/`](src/main/java/com/demo/deployment/health) |

## Run

```bash
./gradlew bootRun
```

Then in another terminal, run the demo scripts (they use `curl` + `jq`):

```bash
./scripts/01-health-probes.sh
./scripts/02-graceful-shutdown.sh   # see header — needs separate app process
./scripts/03-feature-flags.sh
./scripts/04-blue-green-canary.sh
```

---

## 1. Tiered health probes

Three independent probe groups, each backed by different indicators:

- `GET /actuator/health/startup` — DOWN until app warmup completes
  (configurable via `WARMUP_MS`, default 3s). Kubernetes treats failures
  here as "not ready yet" instead of "restart me".
- `GET /actuator/health/liveness` — UP unless the process is hung.
  **Does NOT depend on DB or downstreams** — restarting yourself doesn't
  fix someone else's outage.
- `GET /actuator/health/readiness` — UP only when DB and downstream are
  healthy. Drops to DOWN ⇒ load balancer pulls the pod out of rotation.

Flip dependencies at runtime to watch the probes react:

```bash
curl -X POST 'localhost:8080/admin/deps/db?up=false'
curl localhost:8080/actuator/health/liveness    # still UP
curl localhost:8080/actuator/health/readiness   # 503
```

## 2. Graceful shutdown

Spring Boot's `server.shutdown: graceful` handles connection-level draining.
[`GracefulShutdownListener`](src/main/java/com/demo/deployment/shutdown/GracefulShutdownListener.java)
adds the orchestration on top:

1. On `SIGTERM` (`ContextClosedEvent`), publish
   `ReadinessState.REFUSING_TRAFFIC` so the readiness probe returns 503.
2. Sleep `app.shutdown.drain-grace-ms` (default 2s) so the LB observes the
   new readiness state before connections start closing.
3. Spin-wait for in-flight requests (tracked by
   [`InFlightRequestFilter`](src/main/java/com/demo/deployment/shutdown/InFlightRequestFilter.java))
   to drain, bounded by `spring.lifecycle.timeout-per-shutdown-phase`.

The `02-graceful-shutdown.sh` script fires 5 parallel 8s requests, sends
SIGTERM, and verifies all 5 complete normally while new requests are refused.

## 3. Feature flag — deploy ≠ release

In-memory `FeatureFlagService` (stand-in for Unleash / LaunchDarkly) with:

- master `enabled` killswitch
- `rolloutPercent` (0–100), bucketed by `hash(flagKey + userId)` so a given
  user is **sticky** — they don't flip between requests

The `/checkout` endpoint serves `v1-legacy-checkout` or `v2-new-checkout`
depending on the `new-checkout` flag. The v2 code is already deployed; the
team controls release independently:

```bash
# v2 deployed but dark
curl -X POST 'localhost:8080/flags/new-checkout?enabled=true&rolloutPercent=0'

# 10% canary
curl -X POST 'localhost:8080/flags/new-checkout?enabled=true&rolloutPercent=10'

# instant rollback — no redeploy
curl -X POST 'localhost:8080/flags/new-checkout?enabled=false&rolloutPercent=100'
```

## 4. Blue-Green / Canary routing

[`TrafficRouter`](src/main/java/com/demo/deployment/canary/TrafficRouter.java)
is an in-process simulation of an LB / service mesh sitting in front of two
backend versions (BLUE = v1, GREEN = v2). Two modes:

- **BLUE_GREEN** — 100% to `activeColor`. Flipping the active color is the
  cutover. Old version stays running so rollback is instant.
- **CANARY** — `canaryWeight` % of requests go to GREEN, the rest to BLUE.
  Sticky per-user via `hash(userId)`.

Endpoints (also see `scripts/04-blue-green-canary.sh`):

```bash
curl 'localhost:8080/api/hello?userId=alice'           # served by router
curl 'localhost:8080/router/config'                    # current state + hit counts

curl -X POST 'localhost:8080/router/mode?mode=CANARY'
curl -X POST 'localhost:8080/router/canary-weight?weight=10'
curl -X POST 'localhost:8080/router/active-color?color=GREEN'
```

> The router simulates both backends in-process so the demo runs in one JVM.
> To wire it to real instances, replace the `route()` body with an HTTP
> forward to the chosen color's URL — the routing decision logic stays the
> same.

---

## Config knobs

All env-var driven (see [`application.yml`](src/main/resources/application.yml)):

| Var | Default | What it controls |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `INSTANCE_ID` | `blue-1` | identity logged on every line + returned in responses |
| `COLOR` | `BLUE` | this instance's color label |
| `APP_VERSION` | `v1` | this instance's version label |
| `WARMUP_MS` | `3000` | startup probe stays DOWN this long |
| `DRAIN_GRACE_MS` | `2000` | shutdown waits this long after readiness=503 before draining |
