# ISSUE — Real-time fan-out of market data to many clients

> **One-line problem.** Push live prices from several exchange feeds to thousands
> of browser clients, from a single service instance, without a thread melting
> down and without one slow client stalling everyone else.

---

## 1. Context

A trading/analytics UI needs to show live cryptocurrency prices. Prices come from
public exchange WebSocket feeds (Binance, Coinbase, …). Each feed pushes many
ticks per second per symbol. The UI must reflect them in near real time.

The naive shapes all fail at scale:

| Naive approach | Why it breaks |
|---|---|
| Browser polls a REST endpoint every N ms | Wasteful, laggy, and hammers the server. 5000 clients × 10 req/s = 50k req/s of mostly-unchanged data. |
| Browser opens its own WS to each exchange | Leaks exchange topology to the client, multiplies upstream connections (5000 tabs → 5000 Binance sockets → rate-limited/banned), no normalization. |
| Server uses thread-per-connection (MVC + `SseEmitter`) | Tomcat's worker pool saturates at a few hundred–thousand held-open connections. Idle streaming connections each pin a thread. |

## 2. The hard problem, decomposed

The single "stream prices to everyone" goal hides **five** distinct sub-problems.
Each one is a place where a simple implementation quietly falls over.

### P1 — Connection amplification (fan-out)
One server must hold **thousands of long-lived, mostly-idle** downstream
connections open at once. Thread-per-connection models allocate ~1 thread (and its
stack) per connection; memory and scheduler overhead cap you well before the
network does.

### P2 — Source multiplexing (fan-in)
Prices arrive from **N independent async upstreams** (2 today, more later), each
with its own protocol quirks (Binance auto-subscribes via URL; Coinbase needs an
explicit subscribe frame after handshake). They must be merged into one normalized
stream without a tangle of callbacks and shared mutable state.

### P3 — Upstream connection economy
Every downstream client must **not** translate into an upstream connection. 5000
browsers must share **one** WebSocket per exchange. The upstream connection count
must be decoupled from the downstream client count.

### P4 — Backpressure & the slow-consumer problem
Upstream can emit 100 ticks/s for BTC. A client on mobile, or a client that only
wants 2 updates/s, must **not** force the server to buffer unboundedly, and a
single stuck client must **not** stall delivery to the other 4999. We need
per-client, per-symbol rate control and bounded, drop-oldest buffers.

### P5 — Cold-start emptiness & connection liveness
A client that connects mid-session would otherwise stare at a blank UI until the
next upstream tick. And long-lived streaming connections get silently killed by
idle timeouts in proxies/load balancers. We need an instant warm-up snapshot and a
keep-alive heartbeat.

## 3. What we are protecting

The scarce/at-risk resources this design defends:

- **Server threads** — the primary bottleneck of the naive model. Must stay flat
  (tens of threads) as connections grow (thousands).
- **Server heap** — bounded buffers; no unbounded queue per slow client.
- **Upstream exchange connections** — a rate-limited, bannable external resource.
  Kept at exactly one per exchange per instance.
- **Delivery isolation** — one client's slowness must never become another
  client's latency.
- **Perceived latency / first paint** — a new client must see data immediately.

## 4. Success criteria

- **5000+ concurrent SSE clients** served from one JVM on **~30–40 threads** and
  **< 200 MB heap**.
- Exactly **one upstream WS connection per exchange**, regardless of client count.
- A slow/stuck client is dropped-oldest, not allowed to grow server memory or
  delay others.
- A new client renders a full table **immediately** (snapshot), then updates live.
- Upstream disconnects (exchanges drop sockets ~daily) **auto-recover** with
  backoff, no manual intervention.

## 5. Explicitly out of scope (for this PoC)

- Authentication / authorization / per-user entitlements.
- Persistence / historical queries (see extensions: R2DBC, OHLC).
- Cross-instance state and horizontal scale — that is the subject of
  [CONSISTENCY.md](CONSISTENCY.md), which is where this design's assumptions stop
  holding and must be revisited.

---

See [TECHNICAL.md](TECHNICAL.md) for the solution shape and how each sub-problem
above is solved, and [CONSISTENCY.md](CONSISTENCY.md) for what changes when you run
more than one instance.
