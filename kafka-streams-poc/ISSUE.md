# ISSUE — What problem is this POC actually solving?

> A single sentence, if you only read one line: **We need to compute correct, low-latency, stateful analytics over an unbounded stream of order/payment events without losing data, without stalling on bad records, and without pretending a single JVM can hold the truth.**

Everything else in this repo — the topologies, DLQ, exception handlers, interactive queries, docker-compose — is downstream of that sentence.

---

## 1. The business shape of the problem

Imagine the order pipeline at a brokerage / e-commerce backend:

- Users place **orders** at high frequency, keyed by `orderId`.
- Payments arrive **later** on a separate topic, keyed by `paymentId`, and reference the `orderId`.
- Reference data — **products** and **users** — changes rarely but must be joined onto every order.
- Downstream teams need:
    - **Real-time revenue by category** (dashboards, alerts).
    - **Per-user activity counts** on a rolling window (fraud, abuse detection).
    - **Per-user sessions** — grouped bursts of activity separated by idle gaps.
    - **Completed orders** (order ⨝ payment) with **end-to-end latency**.

The naive answer is "throw it in a database and run cron jobs". That fails on:

- **Volume** — thousands of events/second, agg tables can't be recomputed on read.
- **Latency** — SLAs on dashboards and alerting are in seconds, not minutes.
- **Correctness** — payments can arrive before or after orders, out of order across partitions.
- **Ops cost** — a batch cron that re-scans a giant fact table each minute is a room-heater, not a system.

## 2. Hard problems inside the "just use Kafka Streams" answer

Kafka Streams is the right tool. But saying "use Kafka Streams" understates several concrete things this POC has to *actually* get right:

### 2.1 Correctness under bad input (poison pill problem)

If a single record on `orders.v1` can't be deserialized (bad schema, truncated JSON, a rogue producer), the default behavior is to **fail the stream thread**. That thread crashes, the partition it owns stops progressing, and every downstream consumer of the derived topics stalls behind it.

We must:
- Route the un-parseable bytes somewhere for triage (a DLQ) with enough origin metadata to replay.
- Keep the stream thread alive and moving forward.
- Never do so silently — bad-record throughput must be observable.

See [DlqDeserializationExceptionHandler.java](src/main/java/com/vndirect/kstreams/error/DlqDeserializationExceptionHandler.java).

### 2.2 Correctness under out-of-order events

Payments and orders live on different topics with different producers, different partitions, and different latencies. A payment can be observed *before* its order in wall-clock time if the payment producer is faster or the order broker had a hiccup.

Windowed joins and aggregations must:
- Use **event-time**, not wall-clock time.
- Include **grace periods** so slightly-late records are still counted, without holding state forever.
- Preserve the **join window** (10 minutes here) so late payments still match their order.

See `JOIN_WINDOW` / `JOIN_GRACE` in [OrderPaymentJoinTopology.java](src/main/java/com/vndirect/kstreams/topology/OrderPaymentJoinTopology.java) and `GRACE_PERIOD` in [WindowedAggregationsTopology.java](src/main/java/com/vndirect/kstreams/topology/WindowedAggregationsTopology.java).

### 2.3 Reference-data joins without co-partitioning

`orders.v1` is keyed by `orderId`. To enrich it with `Product`, the naive join requires the orders stream to be re-keyed by `productId` first — which triggers a repartition topic, extra network, and coordination between instances.

We must:
- Enrich orders with product/user metadata without re-shuffling the orders stream.
- Accept the memory cost of a **broadcast copy** on every instance so lookups are local.
- Handle missing reference rows (a brand-new product) without dropping the order.

See the `GlobalKTable` usage in [OrderEnrichmentTopology.java](src/main/java/com/vndirect/kstreams/topology/OrderEnrichmentTopology.java).

### 2.4 State that survives restarts

Every windowed aggregate and every join holds **state** on disk (RocksDB). If the pod restarts:

- Cold-loading gigabytes of state from the changelog topic takes minutes.
- During that time, that partition's data is unavailable to interactive queries.
- Aggregates can double-count or lose entries if EOS isn't set up right.

We must:
- Persist state to durable local disk (`state.dir`).
- Let Kafka Streams rebuild from **changelog topics** on cold start.
- Decide explicitly on `at_least_once` vs `exactly_once_v2` and understand the trade-offs.

See `STATE_DIR_CONFIG` and `PROCESSING_GUARANTEE_CONFIG` in [KafkaStreamsConfig.java](src/main/java/com/vndirect/kstreams/config/KafkaStreamsConfig.java).

### 2.5 Serving the results without a separate database

The aggregates live in state stores inside the streams app. If we teach a downstream service to read a Postgres materialized view instead, we've just re-invented the batch pipeline we were trying to avoid.

We must:
- Expose **Interactive Queries** so callers can hit the JVM that owns a key.
- (Eventually) route the caller to the right instance when we scale out — this POC does not do that yet, see [CONSISTENCY.md](CONSISTENCY.md).

See [StateStoreController.java](src/main/java/com/vndirect/kstreams/api/StateStoreController.java).

### 2.6 One thread dying can't kill the app

A NPE, a `ClassCastException`, or a transient network blip during a commit will crash a stream thread. The default is **the JVM keeps running with fewer threads until zero**, then silently stops making progress.

We must:
- Install an **uncaught-exception handler** that replaces the dead thread.
- Fail fast on truly fatal cases (broker gone, auth revoked) instead of masking them.

See `streamsCustomizer` in [KafkaStreamsConfig.java](src/main/java/com/vndirect/kstreams/config/KafkaStreamsConfig.java).

### 2.7 Production exception handler for produce-side failures

If we try to publish a record that's too large, the default handler fails the stream thread. But `RecordTooLargeException` on a single bad record shouldn't take down a whole pipeline — it should be dropped with an alert.

See [LoggingProductionExceptionHandler.java](src/main/java/com/vndirect/kstreams/error/LoggingProductionExceptionHandler.java).

## 3. What this POC deliberately does NOT solve

Being explicit about the boundary matters as much as what's inside it:

- **No schema registry.** Producers publish JSON; there's no Avro/Protobuf compatibility check. A misbehaving producer's records land in the DLQ, not the topology.
- **No multi-DC replication.** Single Kafka cluster, single region. See [CONSISTENCY.md](CONSISTENCY.md) for what changes if you go multi-region.
- **No Interactive Queries routing.** When you scale beyond one pod, `GET /api/state/users/U-1001` hits *whichever* pod K8s picks — which may not own that key's partition. A real deployment needs `KafkaStreams.queryMetadataForKey` + an HTTP hop.
- **No end-to-end auth.** No SASL/mTLS on the broker; the actuator is wide open. Fine for a local docker-compose demo; a compliance blocker for anything else.
- **No exactly-once by default.** `processing.guarantee=at_least_once` is the shipping default. EOS is a single-config change but has cost — see [TECHNICAL.md](TECHNICAL.md).
- **No custom timestamp extractor.** We trust `orderedAt` / `paidAt` implicitly via record timestamps. If a producer sets a broken timestamp, windows misbehave.

## 4. Why this list matters

Everything in the POC — every config knob, every handler class, every test — traces back to one of the seven concerns above. Read [TECHNICAL.md](TECHNICAL.md) for the "how" side, and [CONSISTENCY.md](CONSISTENCY.md) for what changes the moment there are two pods instead of one.
