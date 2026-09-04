# TECHNICAL — Solution shape & how each sub-problem is solved

This document is the engineering companion to [ISSUE.md](ISSUE.md). It explains the
**shape** of the solution, assigns **key technology by responsibility**, walks
through **how each sub-problem (P1–P5)** is solved in code, and closes with the
**tech debt** we knowingly accept.

---

## 1. Solution shape (the mental model)

One JVM. Three reactive stages. Data flows left-to-right; **no stage owns a thread
per connection.**

```
  INGEST (fan-in)              AGGREGATE (share)           EDGE (fan-out)
  ─────────────────           ──────────────────          ────────────────────
  Binance  WS ─┐                                          ┌─► SSE client #1
               ├─ Sinks.Many ─┐   Flux.merge(...)         ├─► SSE client #2
  Coinbase WS ─┘  (multicast) ├─►  .doOnNext(cache) ──────┼─► SSE client #3
                              │    .share()  (hot)        │      …
             1 WS / exchange  │         │                 └─► SSE client #5000
                              │         ├─ latest snapshot (ConcurrentHashMap)
                              │         │
                              │         └─ per-client: filter → groupBy(symbol)
                              │                        → sample(1000/maxHz)
                              │                        → concat(snapshot, live)
                              │                        → mergeWith(heartbeat)
                              ▼
                    all of this runs on the Netty event loop
                    (~CPU-core count of threads), never blocking
```

**Invariants that make it scale:**

1. **One upstream WS per exchange**, independent of downstream count.
2. **One Netty event loop** (≈ core count) handles every socket read/write.
3. **Per-subscriber operators** (`filter`, `groupBy`, `sample`, `concat`,
   `mergeWith`) are lazy and thread-free — they compose a plan, they don't spawn
   workers.

## 2. Key technology by responsibility

| Responsibility | Technology | Why this and not the obvious alternative |
|---|---|---|
| Non-blocking transport (up & down) | **Reactor Netty** event loop | Event-loop model decouples connection count from thread count — the whole point vs. Tomcat's thread-per-request. |
| Upstream WS client | **`ReactorNettyWebSocketClient`** | Same event loop as the server; the inbound tick stream is a `Flux`, composable with everything else. |
| 1-producer → N-consumer hub | **`Sinks.Many.multicast().onBackpressureBuffer(1024, false)`** | Multicast = many subscribers off one source; drop-oldest bounds memory and isolates slow consumers (P4). |
| Fan-in of N exchanges | **`Flux.merge(List<Flux>)`** | Merges N upstreams into one pipeline in one line; adding an exchange = add a `@Service`. |
| Hot, share-once stream | **`.share()`** (`publish().refCount(1)`) | Every SSE client reads one merged stream, not N private copies; unsubscribes upstream when the last client leaves. |
| Warm-start snapshot | **`ConcurrentHashMap` + `Flux.concat(snapshot, live)`** | New clients get last-known price per symbol instantly (P5) without replaying history. |
| Per-client rate control | **`groupBy(symbol).flatMap(g -> g.sample(window))`** | Per-symbol throttle applied lazily per connection; costs zero extra threads (P4). |
| Client filtering | **`filter()`** on symbol/source query params | Server-side selection; client controls its own slice without new endpoints. |
| One-way push to browser | **Server-Sent Events** (`Flux<ServerSentEvent<T>>`, `text/event-stream`) | For one-way streaming, SSE needs no protocol upgrade; browser consumes it in ~4 lines of `EventSource`. |
| Liveness across proxies | **`mergeWith(heartbeat)`** (15s `ping` comment) | Prevents idle-timeout closure by intermediaries (P5). |
| Resilience | **`Retry.backoff(MAX, 1s).maxBackoff(30s)`** | Exchanges drop sockets ~daily; declarative reconnect with backoff, no imperative loop. |
| Observability | **Actuator + Micrometer + Prometheus** + custom subscriber counter | Proves the scaling claim (threads/heap/subscribers) with real numbers. |
| Extensibility seam | **`ExchangeStream` interface + `List<ExchangeStream>` injection** | New exchange auto-discovered by the aggregator; zero controller changes (P2). |

## 3. How each sub-problem is solved

### P1 — Connection amplification (fan-out) → event loop, not threads
SSE responses are `Flux<ServerSentEvent<PriceTick>>`. Reactor Netty writes to each
of the thousands of held-open sockets from its event loop; an idle streaming
connection pins **no thread**. Result: 5000 clients on ~30–40 threads.
*Code:* [`PriceController.stream`](src/main/java/com/demo/cryptodashboard/controller/PriceController.java).

### P2 — Source multiplexing (fan-in) → merge behind one interface
Each exchange implements [`ExchangeStream`](src/main/java/com/demo/cryptodashboard/service/ExchangeStream.java).
The aggregator injects `List<ExchangeStream>` and does `Flux.merge(sources)`.
Protocol quirks stay inside each service: Binance subscribes via the connect URL;
Coinbase composes `session.send(subscribe).then(session.receive())` in one reactive
chain — no callbacks, no shared mutable state.
*Code:* [`PriceAggregatorService.init`](src/main/java/com/demo/cryptodashboard/service/PriceAggregatorService.java),
[`CoinbaseStreamService.connect`](src/main/java/com/demo/cryptodashboard/service/CoinbaseStreamService.java).

### P3 — Upstream connection economy → Sinks + share()
Each stream service owns **one** WS connection and publishes ticks into a
`Sinks.Many` multicast hub. The aggregator's `.share()` keeps a single subscription
to the merged stream. So N SSE clients → 1 shared pipeline → 1 WS per exchange.
*Code:* `Sinks.many().multicast()` in the stream services; `.share()` in the aggregator.

### P4 — Backpressure & slow consumers → drop-oldest + per-key sample
Two independent defenses:
- **At the hub:** `onBackpressureBuffer(1024, false)` bounds the buffer and drops
  the *oldest* tick when a consumer lags — memory stays bounded, a stuck client
  can't stall others (for stale market data, newest wins anyway).
- **Per client:** `groupBy(symbol).sample(1000/maxHz ms)` caps the emit rate
  *per symbol per connection*. Upstream still receives everything; the client just
  gets its requested slice.
*Code:* stream services (buffer) + [`PriceController.stream`](src/main/java/com/demo/cryptodashboard/controller/PriceController.java) (sample).

### P5 — Cold-start & liveness → snapshot + heartbeat
- **Warm-up:** the aggregator caches the last tick per `symbol@source` in a
  `ConcurrentHashMap`. On connect, `Flux.concat(snapshot, live)` sends that
  snapshot first — instant first paint.
- **Heartbeat:** `mergeWith` interleaves a 15s SSE `ping` comment so proxies/LBs
  see traffic and don't close the idle connection.
*Code:* `snapshot()` / `heartbeats()` in the aggregator; `concat` + `mergeWith` in the controller.

## 4. Data contract

`PriceTick` is a normalized record — the boundary that isolates exchange-specific
JSON from the rest of the system:

```java
record PriceTick(String symbol, double price, double changePercent,
                 double volume, String source, Instant timestamp) {}
```

Normalization (e.g. Coinbase `BTC-USD` → `BTCUSDT`, deriving `changePercent` from
`open_24h`) happens **inside** each stream service, so the aggregator and
controller never see exchange-specific shapes.

## 5. Tech debt we knowingly accept

These are conscious trade-offs for a single-instance PoC, not oversights. Each is
something a production build must revisit.

| # | Debt | Why it's acceptable here | What it costs / when it bites |
|---|---|---|---|
| D1 | **Single-instance state.** Snapshot map, multicast sinks, and subscriber counter are all in-JVM. | PoC runs as one process; goal is to prove thread/heap scaling. | Breaks the moment you run ≥2 instances — see [CONSISTENCY.md](CONSISTENCY.md). This is the biggest debt. |
| D2 | **Per-instance upstream connections.** Each JVM opens its own WS per exchange. | Fine for one instance. | At K pods you get K sockets per exchange → rate limits / bans. Needs an ingest/edge split. |
| D3 | **Drop-oldest = lossy.** Overflow silently drops ticks. | Correct for *display* of live market data (latest wins). | Wrong for anything needing every tick (audit, OHLC, billing). Would need replay/persistence. |
| D4 | **No persistence / replay.** A late joiner gets snapshot + live only, no history. | UI only needs "now". | No historical charts; a full restart loses the snapshot until feeds warm up. |
| D5 | **`new ObjectMapper()` per service; hand-rolled JSON parse.** | Small, readable, no hot-path allocation concerns at PoC scale. | Duplicated config; brittle to upstream schema drift. Prefer a shared, tuned mapper + typed DTOs. |
| D6 | **Fixed symbol lists in code.** Symbols are hard-coded constants per service. | Keeps the demo self-contained. | No runtime add/remove of symbols; redeploy to change coverage. |
| D7 | **No auth / rate-limit / entitlements on the SSE endpoint.** | Public market data, demo only. | Anyone can open unbounded streams; production needs authn + per-user connection caps. |
| D8 | **Backpressure isolation depends on drop-oldest, not true reactive pull.** SSE over Netty write is fast, but a truly wedged socket relies on the bounded buffer. | Bounded buffer + heartbeat detect and cap the damage. | A pathological client is bounded, not gracefully shed; consider explicit idle/timeout eviction. |
| D9 | **Subscriber count is a process-local `AtomicLong`.** | Enough to sanity-check one JVM in the load test. | Not a global number across instances; needs aggregation (see CONSISTENCY.md). |

## 6. When this design is the right tool (and when it isn't)

**Right fit** (what this PoC exercises): streaming over SSE/WebSocket, fan-out to
many idle connections, fan-in from multiple async sources, API gateways/BFFs, LLM
token streaming, long-polling.

**Wrong fit:** simple CRUD over a blocking DB (use MVC + virtual threads on Java
21+), workloads dominated by blocking dependencies (legacy JDBC/SOAP/file I/O), or
teams with no Reactor experience (dense stack traces, awkward debugging — a real
learning tax).

---

*Next:* [CONSISTENCY.md](CONSISTENCY.md) — what breaks and what to add when you
scale this to multiple pods/VMs.
