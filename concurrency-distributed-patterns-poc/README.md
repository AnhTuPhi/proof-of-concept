# concurrency-distributed-patterns-demo

A runnable POC of five concurrency / distributed-systems patterns in one Spring
Boot app. Java 21, no external infrastructure required — H2 in-memory + an
in-process simulation of the Redlock quorum stores.

Patterns:

1. **Distributed Lock (Redlock)** — quorum-based locking across N independent stores
2. **Optimistic Locking** with JPA `@Version`
3. **Transactional Outbox** — atomic state + message via a single local transaction
4. **Saga (orchestration)** — distributed transaction without 2PC, with compensations
5. **CQRS + Event Sourcing** — events as source of truth, projection for reads

## Run it

```bash
./gradlew bootRun
```

Then open <http://localhost:8080/> for an index of routes. H2 console at
`/h2` (JDBC URL `jdbc:h2:mem:demo`, user `sa`, no password).

```bash
./gradlew test         # unit tests for each pattern
./gradlew bootJar      # build runnable jar in build/libs/
```

---

## 1 · Distributed Lock (Redlock)

`com.demo.patterns.distributedlock`

[`RedlockManager`](src/main/java/com/demo/patterns/distributedlock/RedlockManager.java)
implements Antirez's algorithm against N `LockNode`s (each one an independent
in-memory map standing in for a Redis instance):

1. Pick a unique token, try `SET NX PX` on every node.
2. If at least **quorum** acquired AND elapsed < TTL − clock drift, the lock
   is held with `validity = ttl − elapsed − drift`.
3. Otherwise release everywhere and fail.

Configured for N=5, quorum=3 in `application.yml`.

```bash
# 1. Acquire and "work" for 500ms
curl -X POST 'localhost:8080/demo/redlock/work?key=order-42&workerId=A&workMs=500'

# 2. In parallel, fire several workers — only one wins at a time
for i in 1 2 3 4 5; do
  curl -s -X POST "localhost:8080/demo/redlock/work?key=order-42&workerId=W$i&workMs=300" &
done; wait

# 3. Knock 2 of 5 nodes offline — quorum still reachable
curl -X POST 'localhost:8080/demo/redlock/nodes/node-0/down?down=true'
curl -X POST 'localhost:8080/demo/redlock/nodes/node-1/down?down=true'
curl -X POST 'localhost:8080/demo/redlock/work?key=k&workMs=50'   # still works

# 4. Knock a third node offline — no quorum, acquire fails
curl -X POST 'localhost:8080/demo/redlock/nodes/node-2/down?down=true'
curl -X POST 'localhost:8080/demo/redlock/work?key=k&workMs=50'   # 409
```

**What's simplified vs production:** real Redlock talks to N separate Redis
processes over the network; here they're in-memory maps. The quorum +
validity + token-based release logic is faithful.

---

## 2 · Optimistic Locking with `@Version`

`com.demo.patterns.optimisticlock`

[`Product`](src/main/java/com/demo/patterns/optimisticlock/Product.java) has a
JPA `@Version` field. Hibernate adds `WHERE version = ?` to every update; if
two transactions race, one sees zero rows updated and gets
`OptimisticLockException` — no lost writes.

```bash
# Seed a product
curl -X POST 'localhost:8080/demo/optimistic/products?name=Widget&stock=10'

# Hammer it with 20 parallel decrements
curl -X POST 'localhost:8080/demo/optimistic/products/1/concurrent-decrement?threads=20&amount=1'
# → {"successes":4,"conflicts":16,"finalStock":6,"finalVersion":4}
#   note: successes + finalStock == initial stock. No lost updates.
```

The conflict count varies by run, but `successes` always matches the actual
decrement applied (`initialStock − finalStock`). With NO `@Version`, threads
would race on `read → modify → write` and the final stock would drift below
what was actually sold.

---

## 3 · Transactional Outbox

`com.demo.patterns.outbox`

[`OrderService.placeOrder`](src/main/java/com/demo/patterns/outbox/OrderService.java)
writes the order row AND an `OutboxEvent` row in a single local transaction.
[`OutboxRelay`](src/main/java/com/demo/patterns/outbox/OutboxRelay.java) polls
unprocessed events on a fixed schedule and "publishes" them to an in-memory
event bus — at-least-once.

```bash
# Place an order — outbox row written in the same TX
curl -X POST 'localhost:8080/demo/outbox/orders?customer=alice&product=book&quantity=2'

# Pause the relay to watch the outbox accumulate
curl -X POST 'localhost:8080/demo/outbox/relay/pause?paused=true'
for i in 1 2 3; do
  curl -X POST "localhost:8080/demo/outbox/orders?customer=u$i&product=p&quantity=1"
done
curl localhost:8080/demo/outbox/pending     # 3 unprocessed

# Unpause — relay drains them
curl -X POST 'localhost:8080/demo/outbox/relay/pause?paused=false'
curl localhost:8080/demo/outbox/published
```

**What's simplified vs production:** the bus is an in-process queue — swap
for Kafka/RabbitMQ. The relay uses simple polling; the next step is CDC via
Debezium reading the WAL.

---

## 4 · Saga (orchestration)

`com.demo.patterns.saga`

[`OrderSagaOrchestrator`](src/main/java/com/demo/patterns/saga/OrderSagaOrchestrator.java)
runs four steps — charge payment → reserve inventory → create shipping label
→ confirm — each pushing a compensation onto a stack. On any failure the
stack unwinds in reverse: void shipping, release inventory, refund payment.

Inject a failure at any step via `?failAt=`:

```bash
# Happy path
curl -X POST 'localhost:8080/demo/saga/order?customer=alice&amount=100&sku=SKU-A'

# Inventory fails after charge → payment refunded
curl -X POST 'localhost:8080/demo/saga/order?customer=alice&amount=100&sku=SKU-A&failAt=inventory'

# Shipping fails after both → inventory released, then payment refunded
curl -X POST 'localhost:8080/demo/saga/order?customer=alice&amount=100&sku=SKU-A&failAt=shipping'

# Verify state — balances and stocks restored
curl localhost:8080/demo/saga/state
```

The response includes the step log so you can see compensations execute in
reverse order. Compensations are idempotent (re-releasing a reservation is a
no-op) so the saga is safe to retry.

**What's simplified vs production:** real sagas usually persist state per
step so they can recover after a crash mid-flight; here the orchestrator is
synchronous and in-memory. Compensations should be idempotent in both
versions — they already are here.

---

## 5 · CQRS + Event Sourcing

`com.demo.patterns.cqrses`

Two distinct models for the same data:

- **Write side**: every command appends an immutable
  [`AccountEvent`](src/main/java/com/demo/patterns/cqrses/AccountEvent.java)
  to the event store. A `UNIQUE (aggregateId, version)` constraint enforces
  optimistic concurrency on the log itself — concurrent writers can't both
  commit version N+1.
- **Read side**:
  [`AccountProjector`](src/main/java/com/demo/patterns/cqrses/AccountProjector.java)
  subscribes to the committed event and updates an `AccountBalanceView`
  table — eventually consistent.

```bash
# Open an account, capture the ID
ACC=$(curl -s -X POST 'localhost:8080/demo/cqrses/accounts?initialDeposit=100' \
      | python3 -c 'import json,sys;print(json.load(sys.stdin)["aggregateId"])')

# Drive some commands
curl -X POST "localhost:8080/demo/cqrses/accounts/$ACC/deposit?amount=50"
curl -X POST "localhost:8080/demo/cqrses/accounts/$ACC/withdraw?amount=30"

# Replayed state from the event store (the write side)
curl "localhost:8080/demo/cqrses/accounts/$ACC/events"

# Projected read model — note 'note' field tells you which side answered
curl "localhost:8080/demo/cqrses/accounts/$ACC/view"

# Try to withdraw more than the balance → 409
curl -X POST "localhost:8080/demo/cqrses/accounts/$ACC/withdraw?amount=999"
```

The event stream is the source of truth — you could blow away the view table
and rebuild it by replaying every event.

**What's simplified vs production:** the projector runs in-process after the
commit; a real system would consume from Kafka/Debezium with a durable
checkpoint so projections survive crashes. Snapshots and event versioning are
out of scope for the POC.

---

## Layout

```
src/main/java/com/demo/patterns
├── PatternsDemoApplication.java
├── common/IndexController.java
├── distributedlock/      # Redlock + N LockNodes + REST
├── optimisticlock/       # Product entity with @Version + decrement demo
├── outbox/               # Order + OutboxEvent + scheduled relay + bus
├── saga/                 # Payment + Inventory + Shipping + orchestrator
└── cqrses/               # AccountEvent store + Aggregate + Projector + view
```

## Why these five together

They are the toolbox you reach for once you accept that **the database is not
the only source of truth** and **two networked services can't share a
transaction**. Each one trades a different axis:

| Pattern              | Trades                                                 |
|----------------------|--------------------------------------------------------|
| Redlock              | strong mutual exclusion ↔ availability if quorum drops |
| `@Version`           | simple ↔ visible-to-app retries on conflict            |
| Outbox               | atomicity ↔ at-least-once delivery / dedupe downstream |
| Saga                 | progress without 2PC ↔ visible intermediate states     |
| CQRS + ES            | auditability + flexible reads ↔ eventual consistency   |
