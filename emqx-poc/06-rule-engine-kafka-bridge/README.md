# POC 06 — Rule Engine + Kafka & Postgres Bridges

> **Goal:** Replace the "we need a microservice to receive MQTT and write to a database" anti-pattern with EMQX's rule engine. **Device PUBLISH → EMQX rule → Kafka topic AND Postgres row, with no service in between.**

## What the rule engine actually is

EMQX 5.x's rule engine is a SQL-like processor that runs inside the broker. For every MQTT message that matches a topic filter, it evaluates a `SELECT ... WHERE ...` and forwards the result to one or more **Actions** (sinks). Connectors are reusable handles to external systems (Kafka, Postgres, HTTP, S3, MongoDB, Redis, ...).

**Why it matters**: most teams write a Spring/Node app to subscribe MQTT and write Kafka. That's a service to run, scale, monitor, and that adds latency. The rule engine does it inside the broker process with batching and back-pressure built in. At 100k msgs/sec a rule-engine sink uses ~1 broker CPU core. A Spring Kafka publisher uses ~4 cores plus the operational cost.

## What this POC provisions

At startup, this Spring app **calls the EMQX Mgmt API** to create:

1. **Connector** `kafka_local` — handle to Kafka at `kafka:9092`
2. **Connector** `pg_local` — handle to Postgres at `postgres:5432`
3. **Action** `to_kafka` — publishes `{key: deviceId, value: payload}` to `iot.telemetry`, partitioned by deviceId
4. **Action** `to_pg` — `INSERT INTO telemetry(device_id, tenant_id, topic, payload, qos) VALUES (...)`
5. **Rule** `rule_telemetry_routing` —
   ```sql
   SELECT * FROM "tenant/+/devices/+/telemetry"
   WHERE payload.metrics.temp_c > 0
   ```
   triggers both actions.

Then the same app **consumes from `iot.telemetry`** so you can see messages flowing without opening a Kafka console.

## Run

```bash
# 1. Bring up the cluster (already includes Kafka + Postgres)
docker compose up -d

# 2. Start this app - it provisions the rules at boot
java -jar 06-rule-engine-kafka-bridge/target/poc-06-rule-engine-kafka-bridge-1.0.0.jar

# 3. Publish a few telemetry messages from any client
mosquitto_pub -h localhost -p 1883 -u backend-svc -P backend-secret \
  -t 'tenant/tenant-a/devices/device-001/telemetry' \
  -m '{"deviceId":"device-001","tenantId":"tenant-a","metrics":{"temp_c":22.5,"humidity":40},"sequence":1}'

# 4. Verify the bridges worked
curl localhost:8106/verify/kafka-count   # consumed from iot.telemetry
curl localhost:8106/verify/pg-count      # rowsInTelemetry
```

## Backpressure (the part that breaks in production)

The two `resource_opts` blocks in the provisioner control this:

```json
{
  "request_ttl": "15s",        // drop a message if it can't be delivered in 15s
  "health_check_interval": "15s",
  "query_mode": "async",       // sync would block the MQTT receive thread
  "worker_pool_size": 8,
  "batch_size": 100,           // batch sends to the sink
  "batch_time": "20ms"         // max wait before flushing a partial batch
}
```

The biggest mistake here is leaving **`query_mode: sync`** with a slow sink. A sync sink blocks the broker's receive workers; a few slow Postgres writes can stall a node. Always use `async` for high-throughput rules.

When the sink is slower than the inbound rate:
- **Memory overload protection** kicks in (we set `buffer.memory_overload_protection: true` for Kafka).
- Messages spill to a disk-backed queue.
- If still over capacity, messages are dropped per `request_ttl`.
- Drops are visible in `emqx_rule_engine_actions_dropped` Prometheus metric — set an alert.

## Rule SQL grammar (the actually-useful subset)

```sql
-- Filter: only high-temp readings
SELECT * FROM "tenant/+/devices/+/telemetry"
WHERE payload.metrics.temp_c > 30;

-- Project / transform: enrich with topic-derived fields
SELECT
  payload.deviceId AS device_id,
  topic_parts(topic, 1) AS tenant_id,
  payload.metrics.temp_c AS temp_c,
  now() AS ingested_at
FROM "tenant/+/devices/+/telemetry";

-- Join with retained messages (rare but powerful)
SELECT
  payload AS current,
  retained_msg(concat(topic, '/baseline')) AS baseline
FROM "tenant/+/devices/+/telemetry";

-- Dead-letter for malformed payloads (use a separate rule):
SELECT topic, payload FROM "$bridges/+/error/#"
```

## What "production-ready" looks like

1. **Version your rules in git** as JSON, push via the Mgmt API from CI. Don't click in the dashboard.
2. **Alarm on `dropped` metric** for every rule.
3. **Use connector pools sized for your sink**, not the broker — `pool_size: 8` to Postgres is enough for 50k msgs/sec; bigger doesn't help because PG is the bottleneck.
4. **Per-action TTL** — different sinks tolerate different lag. Kafka can buffer; HTTP probably can't.
5. **Always have at least one fallback sink** for high-value messages (e.g. write to S3 if Kafka is unavailable).

## Direct bridges vs. rule engine

EMQX 5 also supports "data bridges" that you can subscribe directly to a topic without a rule. That's lower-overhead when you just want to fan out without filtering, at the cost of less flexibility. Use rule engine when:
- You filter (`WHERE`) or transform (`SELECT ... AS ...`)
- You fan out to multiple sinks with one rule
- You want one place for cross-cutting logic (e.g. add `ingested_at` to every record)

Use direct bridges when:
- One topic → one sink, no transform.
- You want the lowest possible latency (rule overhead is ~50µs, direct is ~10µs).
