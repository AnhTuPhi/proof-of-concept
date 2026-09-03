# resilience-patterns-demo

A runnable demo of 5 resilience patterns in a Spring Boot 3.3 service on **Java 21** (virtual threads enabled).

| Pattern | Where to look | What it shows |
|---|---|---|
| Circuit Breaker (Resilience4j) | `service/CircuitBreakerService.java`, `controller/CircuitBreakerController.java` | Fail-fast once a downstream's failure rate crosses a threshold; half-open probing after a cooldown |
| Retry w/ exponential backoff + jitter | `service/RetryService.java`, `controller/RetryController.java`, `application.yml` | Recover from transient failures without retry storms — jitter spreads concurrent retries |
| Bulkhead | `service/BulkheadService.java`, `controller/BulkheadController.java` | One saturated subsystem can't drown the others — independent concurrency budgets |
| Idempotency key | `idempotency/IdempotencyFilter.java`, `controller/PaymentController.java` | Safe POST replay on client retry. Same key + same body ⇒ cached response. Same key + different body ⇒ 409 |
| Rate limiting | `ratelimit/TokenBucket.java`, `ratelimit/SlidingWindowLog.java`, `controller/RateLimitController.java` | Two hand-rolled algorithms — burst-friendly token bucket vs. precise sliding-window log |

## Run

```bash
./gradlew bootRun
```

Server starts on `http://localhost:8080`. Health: `GET /actuator/health`.

---

## 1. Circuit Breaker

```bash
# Watch metrics — the downstream fails ~70% by default
curl -s localhost:8080/circuit/state | jq

# Hammer it — after a few failures the breaker OPENs and you'll see "fallback: circuit open..."
for i in {1..20}; do curl -s localhost:8080/circuit/call; echo; done

# Then verify it's open
curl -s localhost:8080/circuit/state | jq .state

# Heal the downstream and watch the breaker close again after the cooldown
curl -s "localhost:8080/circuit/tune?failureRate=0.0"
sleep 6
for i in {1..10}; do curl -s localhost:8080/circuit/call; echo; done
curl -s localhost:8080/circuit/state | jq .state
```

Config lives in `application.yml` under `resilience4j.circuitbreaker.instances.flakey`. Defaults: open at ≥50% failure rate over the last 10 calls, sit in OPEN for 5s, then admit 3 probe calls in HALF_OPEN.

---

## 2. Retry — exponential backoff + jitter

```bash
# Single client — see how many attempts it takes
curl -s "localhost:8080/retry/call?tag=demo"

# Stampede — 20 virtual-thread clients retrying at once.
# With jitter enabled, their retries are spread across each backoff window
# instead of all firing on the same tick.
curl -s "localhost:8080/retry/stampede?concurrency=20" | jq

curl -s localhost:8080/retry/metrics | jq
```

Config: `application.yml` → `resilience4j.retry.instances.flakeyRetry`. 5 attempts, 200ms base, ×2 backoff, ±50% randomized wait. Only retries `DownstreamUnavailableException` — never blindly retry `IllegalArgumentException` or auth failures.

---

## 3. Bulkhead

```bash
# Saturate service A — 20 callers, only 5 concurrent slots, the rest get rejected fast
curl -s "localhost:8080/bulkhead/saturate-a?concurrency=20&workMillis=800" | jq

# While A is melting, B is still healthy because it has its own budget
curl -s "localhost:8080/bulkhead/b?workMillis=100"

curl -s localhost:8080/bulkhead/state | jq
```

Config: `application.yml` → `resilience4j.bulkhead.instances`. `maxWaitDuration: 0` makes excess callers fail immediately rather than queue (queueing would just shift the pain).

---

## 4. Idempotency key

```bash
KEY=$(uuidgen)

# First POST — actually charges
curl -s -X POST localhost:8080/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" \
  -d '{"amount": 4200, "currency": "USD"}'
echo

# Retry with the same key + same body — replayed from cache (note the Idempotency-Replayed header)
curl -si -X POST localhost:8080/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" \
  -d '{"amount": 4200, "currency": "USD"}' | head -20

# Same key + DIFFERENT body — 409, refuses to silently double-charge
curl -si -X POST localhost:8080/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" \
  -d '{"amount": 99999, "currency": "USD"}' | head -10
```

The filter (`IdempotencyFilter.java`) keys on `Idempotency-Key` and SHA-256 of the body. In-memory cache here; in prod back it with Redis or a unique-constraint table.

---

## 5. Rate limiting

### Token bucket (burst-friendly)

Capacity 10, refill 5 tokens/sec.

```bash
# Burst of 15 immediately — first 10 admitted (capacity), rest get 429
for i in {1..15}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -H "X-Client-Id: alice" localhost:8080/ratelimit/token-bucket
done

# Sustained — 5/sec succeed forever
for i in {1..10}; do
  curl -s -H "X-Client-Id: alice" localhost:8080/ratelimit/token-bucket | jq -c
  sleep 0.2
done

# Different client = independent bucket
curl -s -H "X-Client-Id: bob" localhost:8080/ratelimit/token-bucket
```

### Sliding window log (precise)

20 requests per 10-second window.

```bash
for i in {1..25}; do
  curl -s -o /dev/null -w "%{http_code} " \
    -H "X-Client-Id: alice" localhost:8080/ratelimit/sliding-window
done
echo
```

First 20 get `200`, then `429` until the oldest timestamps fall out of the window.

---

## Build

```bash
./gradlew build
```

## Notes / non-obvious choices

- **Virtual threads** are enabled for Tomcat (`spring.threads.virtual.enabled=true`) and used directly in the `stampede` / `saturate` helpers. They keep the stampede demo realistic — you can fan out hundreds of concurrent retries without thread-pool exhaustion masking the patterns you're trying to observe.
- **Retry only catches `DownstreamUnavailableException`** — be deliberate about retry classes. Blindly retrying everything makes 4xx bugs look like flakiness.
- **Bulkhead uses `maxWaitDuration: 0`** — excess callers fail fast rather than queue. Queueing usually just hides the back-pressure signal.
- **Idempotency cache stores only 2xx responses** — replaying a 500 to a client that's already retrying is pure cruelty.
- **Rate limiters are per-`X-Client-Id`** — in real systems the key is an API key, a JWT `sub`, or the source IP. Never key on something the attacker fully controls without auth in front.
