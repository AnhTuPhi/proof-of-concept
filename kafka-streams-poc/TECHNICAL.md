# TECHNICAL — How the three POCs work

Read [ISSUE.md](ISSUE.md) first for *why*. This document is *how*.

Every section follows the same shape:

1. **The hard problem** — one paragraph on the actual difficulty.
2. **What we're protecting** — the invariant or SLA at risk.
3. **Solution shape** — high level, before code.
4. **Key tech by responsibility** — which class/config does which job.
5. **Sub-problem coverage** — a table that maps concrete pain points to the mechanism that fixes them.
6. **Tech debt** — what we punted, and why the punt is safe (or not).

---

## Cross-cutting: how the topologies fit together

```
┌───────────────┐     ┌───────────────┐
│  products.v1  │     │    users.v1   │  (compacted, keyed by id)
│  (KTable)     │     │   (KTable)    │
└───────┬───────┘     └───────┬───────┘
        │  GlobalKTable       │  GlobalKTable
        │                     │
        ▼                     ▼
    ┌───────────────────────────────┐
    │  OrderEnrichmentTopology       │◄──── orders.v1
    │  (stream ⨝ two GlobalKTables)  │
    └───────┬───────────────────────┘
            │ KStream<String, EnrichedOrder>
            │
            ├──► enriched-orders.v1   (materialized)
            │
            ├──► WindowedAggregationsTopology
            │       ├── tumbling  → category-revenue.v1
            │       ├── hopping   → user-order-counts.v1
            │       └── session   → user-sessions.v1
            │
            └──► OrderPaymentJoinTopology
                   orders ⨝ payments (10-min sliding window)
                   → completed-orders.v1
```

The three topologies **share a single `StreamsBuilder`** ([StreamsTopologyBuilder.java](src/main/java/com/vndirect/kstreams/topology/StreamsTopologyBuilder.java)) so they run inside one `application-id`. The enrichment topology *returns* the `KStream<String, EnrichedOrder>` that the aggregations topology consumes directly — no round-trip through the enriched-orders topic. That's a real production win: one deserialization pass instead of two.

---

## POC 1 — Stream-Table Enrichment (`OrderEnrichmentTopology`)

### The hard problem

Every order needs to be decorated with `productName`, `category`, `userDisplayName`, `userTier`, `userCountry`. The reference data lives in its own topics and changes rarely. The naïve approach is to `KStream.leftJoin(KTable)` — but this requires the orders stream to be **co-partitioned** by `productId` (and again by `userId`), which means Kafka Streams will insert a repartition topic, re-shuffle traffic, and force network cost on every enrichment.

### What we're protecting

- **Throughput** on the orders path — enrichment can't add a shuffle hop.
- **Availability during reference-data cold start** — orders keep flowing even if a product row lands late.
- **Freshness** — a product update must be visible to enrichment within seconds, not on a scheduled reload.

### Solution shape

- Load `products.v1` and `users.v1` as **`GlobalKTable`**s — every instance holds the whole table locally.
- `KStream(orders).leftJoin(GlobalKTable)` — no repartition, lookups are local RocksDB (or in-memory here) reads.
- `leftJoin`, not `join` — a missing product/user does not drop the order. It fills in `"UNKNOWN"`.
- The reference topics are declared with `cleanup.policy=compact` in [TopicConfig.java](src/main/java/com/vndirect/kstreams/config/TopicConfig.java) so the latest value per key is retained forever.

### Key tech by responsibility

| Responsibility | Mechanism | Where |
|---|---|---|
| No co-partitioning cost | `GlobalKTable` | [OrderEnrichmentTopology.java:55](src/main/java/com/vndirect/kstreams/topology/OrderEnrichmentTopology.java) |
| Reference retention | `cleanup.policy=compact` | [TopicConfig.java](src/main/java/com/vndirect/kstreams/config/TopicConfig.java) |
| Missing-data safety | `leftJoin` + `mergeProduct`/`mergeUser` defaults | [OrderEnrichmentTopology.java:79-84](src/main/java/com/vndirect/kstreams/topology/OrderEnrichmentTopology.java) |
| Local read for API | in-memory KV store `products-store` / `users-store` + [StateStoreController.java](src/main/java/com/vndirect/kstreams/api/StateStoreController.java) | |
| Deserialization safety | `JsonSerde` + [DlqDeserializationExceptionHandler.java](src/main/java/com/vndirect/kstreams/error/DlqDeserializationExceptionHandler.java) | |

### Sub-problem coverage

| Sub-problem | Mechanism |
|---|---|
| Orders arrive before their product row | `leftJoin` fills in `UNKNOWN`; order still emitted |
| Reference data cost per instance | `Stores.inMemoryKeyValueStore` (acceptable at demo scale; RocksDB in prod) |
| Reference-data update propagation | GlobalKTable is a KTable — updates stream in as they're produced |
| Two joins in one topology | Fluent `leftJoin` chain; the second lookup runs on the partial-enriched value |

### Tech debt

- **In-memory GlobalKTable store.** Fine for 6 products / 6 users; for a 1M-product catalog, switch to `Stores.persistentKeyValueStore` — see `PRODUCTS_STORE` at [OrderEnrichmentTopology.java:58](src/main/java/com/vndirect/kstreams/topology/OrderEnrichmentTopology.java).
- **JSON serdes.** No schema evolution guardrails. A future producer that adds a required field can break consumers. Real prod → Avro/Protobuf + schema registry.
- **No dead-letter for "reference miss".** We fill in `"UNKNOWN"` — arguably correct, but a real fraud team wants an alert stream when an unknown `productId` appears.

---

## POC 2 — Windowed Aggregations (`WindowedAggregationsTopology`)

### The hard problem

Three different windowing semantics, sharing one input stream, each with different correctness properties:

1. **Tumbling 1-minute per-category revenue** — non-overlapping, one bucket per minute.
2. **Hopping 5-minute per-user counts advancing every 1 minute** — every event ends up in 5 overlapping windows.
3. **Session windows per user (30s inactivity gap)** — window boundaries are data-driven; a late event can *merge* two open sessions.

All three must:
- Use **event time**, not wall-clock time, or a clock skew on producers wrecks the buckets.
- Tolerate a bounded amount of **out-of-order arrival** without losing the record.
- Emit **final results downstream** (as topics + state stores) for both the pipeline and the API.

### What we're protecting

- **Correctness of aggregates** — a dashboard that under-counts is worse than one that's late.
- **Bounded memory** — a session window that never closes is a memory leak with a nice name.
- **Predictable emission** — downstream consumers want windowed records they can dedupe on window bounds.

### Solution shape

- All three groupings **re-key first** (by category, or by userId) so records land in the correct partition for the aggregation.
- Each windowed aggregate has an **explicit grace period** (`GRACE_PERIOD = 10s`). Late records within grace are still counted; past grace, they're dropped.
- Session windows use a **session merger** — when a late event connects two windows, they're merged into one via the `sessionMerger` lambda at [WindowedAggregationsTopology.java:145](src/main/java/com/vndirect/kstreams/topology/WindowedAggregationsTopology.java).
- Aggregation results are **stamped** with `windowStart`/`windowEnd` before emit so downstream systems don't have to know Kafka Streams' `Windowed<K>` API.

### Key tech by responsibility

| Responsibility | Mechanism | Where |
|---|---|---|
| Correct time base | Record timestamps (extractor is default `FailOnInvalidTimestamp`) | Streams config |
| Tumbling window | `TimeWindows.ofSizeAndGrace(1min, 10s)` | [WindowedAggregationsTopology.java:72](src/main/java/com/vndirect/kstreams/topology/WindowedAggregationsTopology.java) |
| Hopping window | `TimeWindows.ofSizeAndGrace(5min, 10s).advanceBy(1min)` | [WindowedAggregationsTopology.java:105](src/main/java/com/vndirect/kstreams/topology/WindowedAggregationsTopology.java) |
| Session window | `SessionWindows.ofInactivityGapAndGrace(30s, 10s)` + merger | [WindowedAggregationsTopology.java:137](src/main/java/com/vndirect/kstreams/topology/WindowedAggregationsTopology.java) |
| State materialization | `Materialized.as(...)` (RocksDB-backed WindowStore/SessionStore) | throughout |
| Named nodes for debugging | `Named.as("rekey-by-category")` etc. | throughout |
| Emit for pipeline + IQ | `toStream().map(stamp-window).to(topic)` + local store lookup in [StateStoreController.java](src/main/java/com/vndirect/kstreams/api/StateStoreController.java) | |

### Sub-problem coverage

| Sub-problem | Mechanism |
|---|---|
| A late event straddles two sessions | Session merger lambda combines them safely |
| A window never closes if traffic is sparse | Grace period bounds retention |
| The API caller doesn't know the window key type | Aggregate values carry `windowStart`/`windowEnd` inside them |
| Hopping windows double-count in totals | Downstream is expected to pick one window per emit period (or dedupe by window start) |
| Category revenue is empty on cold start | `CategoryRevenue.empty()` seeder + `agg.category().isEmpty()` guard |

### Tech debt

- **No custom `TimestampExtractor`.** If a producer sets a garbage record timestamp, windows will misalign. Prod-grade fix: implement a `WallclockOnBadEventTime` extractor or a strict validator.
- **Hopping-window fan-out.** With a 5-min window advancing every 1 min, every record lives in 5 windows → 5× state cost. Fine at demo throughput, revisit at 10k/s.
- **Cache buffering (`STATESTORE_CACHE_MAX_BYTES_CONFIG=10MB`)**. Reduces downstream emit rate but delays intermediate updates by up to `commit.interval.ms`. Doc'd at [AppProperties.java:24](src/main/java/com/vndirect/kstreams/config/AppProperties.java), but no runtime control.

---

## POC 3 — Stream-Stream Join (`OrderPaymentJoinTopology`)

### The hard problem

An order and its payment arrive on separate topics with separate producers, keyed differently (`orderId` vs `paymentId`), and the payment can land anywhere from milliseconds to minutes after the order. We need to emit a `CompletedOrder` — with end-to-end latency — the moment both sides show up, without waiting forever, and without dropping late but valid pairs.

### What we're protecting

- **Payment reconciliation correctness.** A missed match becomes an unpaid order in a downstream ledger.
- **Bounded state.** Every unmatched order lives in the join buffer until the window expires — this is a real memory cost, we must bound it.
- **Latency signal integrity.** `latencyMs = paidAt - orderedAt` — if the timestamps are wrong, so is every downstream KPI.

### Solution shape

- **Re-key payments by `orderId`** so the two sides are co-partitioned on the join key. `payments.selectKey((k, v) -> v.orderId())` produces the required repartition topic automatically.
- Use `JoinWindows.ofTimeDifferenceAndGrace(10min, 30s)` — a symmetric sliding window: any (order, payment) pair whose timestamps differ by ≤10 min is joined; up to 30 s of out-of-order is tolerated.
- Emit a `CompletedOrder` carrying both timestamps and the computed `latencyMs`.

### Key tech by responsibility

| Responsibility | Mechanism | Where |
|---|---|---|
| Co-partitioning | `payments.selectKey(v -> v.orderId())` | [OrderPaymentJoinTopology.java:62](src/main/java/com/vndirect/kstreams/topology/OrderPaymentJoinTopology.java) |
| Windowed join | `JoinWindows.ofTimeDifferenceAndGrace(10min, 30s)` | [OrderPaymentJoinTopology.java:79](src/main/java/com/vndirect/kstreams/topology/OrderPaymentJoinTopology.java) |
| Value join | Lambda producing `CompletedOrder` + `Duration.between(...)` | [OrderPaymentJoinTopology.java:67-78](src/main/java/com/vndirect/kstreams/topology/OrderPaymentJoinTopology.java) |
| Both-side buffering | `StreamJoined.with(...)` — implicit windowed changelog per side | [OrderPaymentJoinTopology.java:80](src/main/java/com/vndirect/kstreams/topology/OrderPaymentJoinTopology.java) |
| Null safety | `.filter((k, v) -> v != null)` on both sides | [OrderPaymentJoinTopology.java:53,58](src/main/java/com/vndirect/kstreams/topology/OrderPaymentJoinTopology.java) |

### Sub-problem coverage

| Sub-problem | Mechanism |
|---|---|
| Payment before order | `JoinWindows` is symmetric — both directions match |
| Payment 9 minutes late | Within 10-min window → still matches |
| Payment 11 minutes late | Past window → orphaned; today it's silently dropped (see debt) |
| Skewed producer clocks | Grace + wall-clock producer timestamps; a bad clock breaks joins (real risk) |
| Duplicate payment | Same `orderId` matches the order again → duplicate `CompletedOrder`; downstream dedup needed |

### Tech debt

- **Inner-join, not left-join.** We only emit `CompletedOrder` when both sides show up. Unpaid orders are invisible here. That's the right default for this pipeline (a separate "unpaid orders" job should read `orders.v1` alone), but it's a landmine if a caller expects "every order eventually appears in `completed-orders.v1`".
- **No dead-letter for join misses.** A payment past the 10-minute grace vanishes. A `leftJoin` variant + emit-on-timeout would surface it; not implemented.
- **Duplicate payments produce duplicate outputs.** Idempotency belongs in the payment producer or in a downstream dedup step — not here.
- **Repartition cost.** `selectKey` on payments creates a repartition topic. At 10k/s that's real broker traffic. Alternative: have payment producers key by `orderId` from the start.

---

## Cross-cutting infrastructure — the "boring but load-bearing" layer

These aren't POCs — they're what makes the POCs safe to run.

### DLQ deserialization handler

**Problem:** one bad record on `orders.v1` would kill the stream thread.

**Solution:** [DlqDeserializationExceptionHandler.java](src/main/java/com/vndirect/kstreams/error/DlqDeserializationExceptionHandler.java) publishes the raw bytes + `dlq.origin.topic/partition/offset/error.class/error.message` headers to `streams.dlq.v1`, then returns `CONTINUE`. If the DLQ producer *itself* fails, we return `FAIL` — silent data loss is worse than a crash.

### Production exception handler

**Problem:** the default handler fails the stream on any produce error, including transient/expected ones.

**Solution:** [LoggingProductionExceptionHandler.java](src/main/java/com/vndirect/kstreams/error/LoggingProductionExceptionHandler.java) drops only `RecordTooLargeException` (single known-bad record), and fails fast on everything else.

### Uncaught exception handler

**Problem:** one thread crashing eventually kills the JVM.

**Solution:** `REPLACE_THREAD` at [KafkaStreamsConfig.java:76-80](src/main/java/com/vndirect/kstreams/config/KafkaStreamsConfig.java). Fatal exceptions still propagate — this is intentional and load-bearing. If your broker's gone, you *want* the app to fail hard so a supervisor restarts it.

### Interactive Queries

**Problem:** if the aggregates live in state stores, how do we serve them?

**Solution:** [StateStoreController.java](src/main/java/com/vndirect/kstreams/api/StateStoreController.java) exposes `/api/state/*`. **This only works for a single instance today** — see [CONSISTENCY.md](CONSISTENCY.md) for why, and what to do next.

### Actuator + Prometheus

**Problem:** operating a Streams app without visibility into thread state, RocksDB stats, and lag is negligent.

**Solution:** Spring Boot Actuator + Micrometer bridge; `/actuator/kafkastreams` exposes stream-thread state; `/actuator/prometheus` gives you the Kafka client metrics for lag/throughput dashboards.

### Overall tech debt to acknowledge

| Debt | Why we punted | Risk if you deploy as-is |
|---|---|---|
| No schema registry | Adds a dependency, POC scope | A bad producer schema change breaks consumers silently |
| No SASL/mTLS on broker | Docker demo | Any cluster tenant can produce to your topics |
| No interactive-queries routing | Single-instance demo | `GET /api/state/...` returns 404 for keys owned by another pod |
| No standby replicas | Single-instance demo | On failover, state store rebuilds from scratch (minutes) |
| `at_least_once` default | EOS costs latency + broker load | Downstream must be idempotent — call this out to consumers |
| In-memory GlobalKTable stores | 6 rows in the demo | RSS blows up on real catalogs — switch to persistent stores |
| Default timestamp extractor | Producers are trusted here | Bad producer clock → misaligned windows, wrong joins |
| Actuator wide-open | Local demo | `/actuator/env` leaks bootstrap servers etc. |

For each of these there's a specific one-file change to make; none require re-architecture. The point of this document is that every debt above is *known*, not stumbled into.
