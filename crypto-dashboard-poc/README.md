# crypto-dashboard-webflux-poc

A proof-of-concept that demonstrates Spring WebFlux strengths through a
real-time cryptocurrency dashboard. One JVM ingests live price ticks from
two public exchange WebSocket feeds (Binance + Coinbase), merges them
reactively, and fans them out to many browser clients over Server-Sent
Events (SSE).

> **Why this PoC exists.** Most WebFlux tutorials show it on a CRUD endpoint,
> which makes it look identical to Spring MVC with extra ceremony. The story
> only becomes convincing on a workload where reactive primitives genuinely
> pay off: lots of streaming connections, fan-in from multiple async sources,
> fan-out to many subscribers, and visible backpressure.

---

## Companion documentation

This README is the operational guide (what it does, how to run it). The problem
framing and design rationale live in dedicated docs:

| Doc | Answers |
|---|---|
| **[ISSUE.md](ISSUE.md)** | *What hard problem is this?* Decomposes the goal into five sub-problems (P1–P5): connection amplification, source multiplexing, upstream connection economy, backpressure/slow-consumers, cold-start & liveness. Defines **what we are protecting** and the success criteria. |
| **[TECHNICAL.md](TECHNICAL.md)** | *What is the solution shape and how does it solve each sub-problem?* Key technology **by responsibility**, a walk-through of P1–P5, and the **tech debt (D1–D9)** we knowingly accept. |
| **[CONSISTENCY.md](CONSISTENCY.md)** | *What breaks when you scale to many pods/VMs?* Why naive replication forks the system into N inconsistent copies, and the ingest/edge + shared-bus + shared-snapshot architecture that fixes it — with Kubernetes and VM specifics. |
| **[explain.html](src/main/resources/static/explain.html)** | Interactive, self-contained visual explainer of the flow and key tech. Served at `http://localhost:8080/explain.html` when the app is running, or open the file directly in a browser. |

---

## Table of contents

1. [What is demonstrated](#what-is-demonstrated)
2. [Architecture](#architecture)
3. [Project layout](#project-layout)
4. [Prerequisites](#prerequisites)
5. [Run](#run)
6. [API reference](#api-reference)
7. [Demo script](#demo-script)
8. [Load test](#load-test)
9. [Observability](#observability)
10. [When NOT to use WebFlux](#when-not-to-use-webflux)
11. [Possible extensions](#possible-extensions)

---

## What is demonstrated

| WebFlux capability | Where in code | What it buys you |
|---|---|---|
| Non-blocking WebSocket client | `BinanceStreamService`, `CoinbaseStreamService` use `ReactorNettyWebSocketClient` | One Netty event loop handles every upstream connection; no thread parked waiting on socket reads. |
| Bidirectional WS pipeline | `CoinbaseStreamService.connect()` composes `session.send(...).then(session.receive(...))` | Outbound subscribe message and inbound tick stream live in the same reactive chain. No callbacks, no shared mutable state. |
| Multicast sink (1 producer to N consumers) | `Sinks.many().multicast().onBackpressureBuffer(1024, false)` in each stream service | One slow downstream consumer cannot stall the others. Old ticks are dropped when buffers overflow, not the whole stream. |
| Resilient reconnect | `Retry.backoff(Long.MAX_VALUE, 1s).maxBackoff(30s)` | Exchanges drop the socket every ~24h. Auto-recover without imperative reconnect loops. |
| Reactive fan-in (N to 1) | `PriceAggregatorService` calls `Flux.merge(sources).share()` | Adds a new exchange = implement `ExchangeStream` and register it as a `@Service`. No changes to the controller. |
| Snapshot warm-up | `PriceAggregatorService.snapshot()` plus `Flux.concat(snapshot, live)` in the controller | New SSE clients see the last known tick per symbol immediately, instead of staring at an empty UI until the next upstream update. |
| Per-key throttling | `PriceController` uses `groupBy(symbol).flatMap(g -> g.sample(window))` | Per-symbol rate cap. Demonstrates real backpressure without dropping the connection. |
| Server-Sent Events | `Flux<ServerSentEvent<PriceTick>>` returned as `text/event-stream` | Browsers consume it with a 4-line `EventSource`. No WebSocket protocol upgrade needed for one-way streams. |
| Keep-alive heartbeat | `Flux.mergeWith(heartbeat)` | Prevents idle-timeout closures by intermediate proxies/load balancers. |
| Subscriber tracking | `doOnSubscribe` / `doFinally` on the SSE flux | Lets you prove the load test numbers with a simple counter endpoint. |

---

## Architecture

```
   Binance WS ──┐                                              ┌── SSE ──► Browser tab #1
                │                                              │
                ├──► Sinks.Many<PriceTick>                     ├── SSE ──► Browser tab #2
                │    (multicast, drop oldest)                  │
   Coinbase WS ─┘             │                                ├── SSE ──► Browser tab #3
                              │                                │
                              ▼                                ├── ... (5000+ subscribers)
                       Flux.merge(sources)                     │
                              │                                │
                              ▼                                │
                          .share()  ◄── one hot stream         │
                              │                                │
              ┌───────────────┴───────────────┐                │
              │                               │                │
              ▼                               ▼                │
       latest snapshot map           per-subscriber pipeline   │
              │                               │                │
              └──── Flux.concat ──► filter ──► groupBy(symbol) │
                                              ▼                │
                                       sample(maxHz window) ───┤
                                              │                │
                                       mergeWith(heartbeat) ───┘
```

Key invariants:

- **One upstream WS per exchange**, regardless of how many browsers are connected.
- **One Netty event loop** (≈ CPU-core count) handles all socket reads.
- **Per-subscriber operators** (`filter`, `groupBy`, `sample`) execute lazily and use no extra threads.

---

## Project layout

```
crypto-dashboard-webflux-poc/
├── pom.xml                                       Spring Boot 3.3.5, Java 21
├── README.md                                     This file (operational guide)
├── ISSUE.md                                      Problem definition (P1–P5)
├── TECHNICAL.md                                  Solution shape, tech-by-responsibility, tech debt
├── CONSISTENCY.md                                Scaling to many pods/VMs
├── src/main/java/com/demo/cryptodashboard/
│   ├── CryptoDashboardApplication.java           Boot entrypoint
│   ├── model/
│   │   └── PriceTick.java                        Normalized tick record
│   ├── service/
│   │   ├── ExchangeStream.java                   Common contract
│   │   ├── BinanceStreamService.java             WS client to Binance public stream
│   │   ├── CoinbaseStreamService.java            WS client + subscribe frame
│   │   └── PriceAggregatorService.java           merge + share + snapshot cache
│   └── controller/
│       └── PriceController.java                  SSE endpoint
├── src/main/resources/
│   ├── application.yml
│   └── static/
│       ├── index.html                            Vanilla-JS dashboard (live demo)
│       └── explain.html                          Interactive flow & tech explainer
└── loadtest/
    └── sse_load_test.sh                          N-client SSE load generator
```

---

## Prerequisites

- **Java 21** (required: virtual threads are not used here, but record patterns are)
- **Maven 3.9+** (or run via IDE)
- **Outbound network** to:
  - `stream.binance.com:9443` (Binance public WS)
  - `ws-feed.exchange.coinbase.com:443` (Coinbase public WS)

No API keys are required - both feeds are public market data.

---

## Run

```bash
# build
mvn -DskipTests package

# run the fat jar
java -jar target/crypto-dashboard-webflux-poc-0.0.1-SNAPSHOT.jar

# or, during development
mvn spring-boot:run
```

Then open the dashboard:

```
http://localhost:8080/index.html
```

You should see rows for BTC, ETH, SOL, XRP, ADA, DOGE, AVAX (and BNB from
Binance only) flashing green/red as ticks arrive from both exchanges.

---

## API reference

| Method | Path | Query params | Description |
|---|---|---|---|
| `GET` | `/index.html` | — | Dashboard UI |
| `GET` | `/api/prices/stream` | `symbols`, `sources`, `maxHz` | SSE feed of price ticks |
| `GET` | `/api/prices/subscribers` | — | Current number of active SSE clients |
| `GET` | `/actuator/health` | — | Health |
| `GET` | `/actuator/metrics/{name}` | — | Single Micrometer metric |
| `GET` | `/actuator/prometheus` | — | Prometheus scrape endpoint |

### `/api/prices/stream` parameters

| Param | Example | Default | Effect |
|---|---|---|---|
| `symbols` | `BTCUSDT,ETHUSDT` | all | Case-insensitive filter. |
| `sources` | `binance` or `binance,coinbase` | all | Filter by exchange. |
| `maxHz` | `2` | `10` | Per-symbol maximum emit rate, implemented via `groupBy().sample(1000/maxHz ms)`. Lower values demonstrate backpressure. |

### SSE event types

| Event | Payload | Meaning |
|---|---|---|
| `price` | `PriceTick` JSON | A new tick |
| `ping` | empty (`:` comment) | Heartbeat every 15s to keep proxies from closing the connection |

`PriceTick` shape:

```json
{
  "symbol":        "BTCUSDT",
  "price":         62760.23,
  "changePercent": -2.60,
  "volume":        7051.80,
  "source":        "binance",
  "timestamp":     "2026-06-19T07:07:25.826Z"
}
```

---

## Demo script

Three scenarios, each highlighting one WebFlux strength.

### 1. Live updates (basic SSE)

Open `http://localhost:8080/index.html`. You'll see two rows per symbol -
one from Binance, one from Coinbase - with slightly different prices,
flashing green/red on every update.

In DevTools, open the Network tab and click the `stream` request:

- `Content-Type: text/event-stream`
- Status stays `pending` (the connection is held open)
- The `EventStream` tab shows each event as it arrives

**Talking point:** the same browser tab is consuming a continuous server
push without polling, without WebSocket, and without holding a thread on
the server.

### 2. Filter and throttle (per-key backpressure)

In the controls bar:

- Set `symbols` to `BTCUSDT` and click *apply*. Only BTC rows survive,
  one per exchange. Compare the price spread between exchanges live.
- Change `maxHz` to `1`. Flashes slow to ~1 per second per symbol.
- Change `maxHz` to `20`. Buttery realtime.

**Talking point:** the upstream rate didn't change. The server is still
receiving every tick from Binance and Coinbase. The throttling is
per-subscriber via `groupBy(symbol).sample(window)` - one operator chain,
applied lazily per connection, costs zero threads.

### 3. Load test (the convincing part)

See [Load test](#load-test) below.

---

## Load test

The included script spawns N concurrent SSE clients (one `curl` process
each) and holds them open for a configurable duration.

```bash
./loadtest/sse_load_test.sh                # default: 1000 clients, 30s
./loadtest/sse_load_test.sh 5000           # 5000 clients, 30s
./loadtest/sse_load_test.sh 5000 60        # 5000 clients, 60s
```

In another terminal, while the load is running:

```bash
# Active server-side subscribers
curl -s localhost:8080/api/prices/subscribers
# 5000

# Live JVM threads
curl -s localhost:8080/actuator/metrics/jvm.threads.live | grep -oE '"value":[0-9.]+' | head -1
# ~30-40

# Heap used
curl -s localhost:8080/actuator/metrics/jvm.memory.used | grep -oE '"value":[0-9.]+' | head -1
```

The result that sells WebFlux:

> **5000 concurrent SSE clients held open on ~30 JVM threads, well under 200MB heap.**

Run the same scenario against a Spring MVC + `SseEmitter` app on the same
hardware and Tomcat's worker pool will saturate somewhere between a few
hundred and a thousand connections, depending on `server.tomcat.threads.max`.

> **Note on the client side.** The bottleneck during the load test is
> usually the *client* (file descriptors, ephemeral port exhaustion).
> Bump `ulimit -n 65535` first. For higher numbers (50k+), run the
> generator from a separate machine.

---

## Observability

Actuator + Micrometer are wired in. Useful endpoints:

```bash
# Subscribers (custom)
curl localhost:8080/api/prices/subscribers

# JVM
curl localhost:8080/actuator/metrics/jvm.threads.live
curl localhost:8080/actuator/metrics/jvm.memory.used
curl localhost:8080/actuator/metrics/process.cpu.usage

# HTTP / WebClient
curl localhost:8080/actuator/metrics/http.server.requests
curl localhost:8080/actuator/metrics/reactor.netty.connection.provider.total.connections

# Full Prometheus scrape
curl localhost:8080/actuator/prometheus
```

Hook the Prometheus endpoint into Grafana and you have a load test
dashboard in minutes.

---

## When NOT to use WebFlux

This PoC celebrates WebFlux, but it has real costs. Skip it when:

- **Simple CRUD over a database.** Spring MVC with virtual threads
  (Java 21+) gives most of the scaling benefit with imperative code that
  is easier to debug.
- **Blocking dependencies dominate.** Legacy JDBC drivers, file I/O
  without async wrappers, SOAP. You can wrap them in
  `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`,
  but you lose most of the benefit and pay all of the cost.
- **The team has not used Reactor before.** Stack traces are dense.
  Debuggers behave oddly. Every new developer pays a learning tax.

The good fits, the ones this PoC exercises:

- Streaming over SSE or WebSocket
- API gateways and BFFs that fan out to many downstream services
- IoT and mobile backends with many idle connections
- Long-polling
- LLM token streaming

---

## Possible extensions

- **Third exchange.** Add a `KrakenStreamService` implementing
  `ExchangeStream`. The aggregator auto-discovers it via the `List<ExchangeStream>`
  injection - zero changes elsewhere.
- **WebSocket instead of SSE.** Useful if you want the client to send
  commands (subscribe / unsubscribe a symbol mid-session) without
  re-opening the connection.
- **OHLC candles.** Add an endpoint built on `Flux.window(Duration.ofMinutes(1))`
  followed by a reduce over each window.
- **Replay buffer for late joiners.** Replace `Sinks.many().multicast()`
  with `Sinks.many().replay().limit(N)` so new clients also get the last
  N ticks of history, not just the snapshot.
- **R2DBC persistence.** Store ticks in PostgreSQL via `r2dbc-postgresql`
  to keep the whole pipeline non-blocking end-to-end. Then expose a
  reactive `/api/prices/{symbol}/history` endpoint.
