# POC 04 — Shared Subscriptions (`$share/group/topic`)

> **Goal:** Demonstrate the MQTT pattern that lets you scale backend consumers behind a broker. This is *exactly* the Kafka consumer-group model — but with one big architectural difference: **the broker, not the consumers, decides who gets each message.**

## The pattern

```
Devices ───▶ telemetry/+/data ───▶ EMQX ───▶ $share/ingest/telemetry/+/data
                                              ├──▶ consumer-1 (msg 1, msg 4, msg 5)
                                              ├──▶ consumer-2 (msg 2, msg 6)
                                              └──▶ consumer-3 (msg 3, msg 7)
```

Multiple consumers subscribe to the **same shared filter** with the **same group**. The broker delivers each message to exactly **one** consumer in that group.

To **fan out** to multiple groups (e.g. one group writes to your data lake, another to your alerting service), each group subscribes with a different `<group>` segment — each group sees a **full copy**.

## Comparison with Kafka

| | Kafka | MQTT shared subs |
|---|---|---|
| Group state | Coordinator + consumer-managed offsets | Broker manages, stateless to consumer |
| Rebalance on consumer add/remove | Yes (stop-the-world) | No (broker just adjusts its routing table) |
| Message ordering | Per-partition | Best-effort, depends on strategy |
| Backpressure | Pull-based; consumer controls | Push-based; relies on inflight window |
| Replay | Yes, by offset | No (unless using EMQX persistent sessions + retained) |

The "no rebalance" property is a huge operational win — consumers can scale up/down without coordination. The downside is no replay; if your downstream is down, you need a queue (Kafka) somewhere.

## Strategies (broker-side config)

In `emqx.conf` or via the Mgmt API:

```hocon
mqtt.shared_subscription_strategy = random  # default
```

| Strategy | When to use |
|---|---|
| `random` | Default. Uniform load, no message-affinity. |
| `round_robin` | Fairer at low rates. Slight broker bookkeeping cost. |
| `sticky` | Hash by subscriber ID. Cache-friendly — the same consumer keeps the same publishers. |
| `hash_clientid` | Hash by publisher's ClientID — when downstream wants ordering per device. |
| `hash_topic` | Hash by topic — when topic implies a partition (e.g. `region/{region}/...`). |

## Run

```bash
# Start a consumer group of 4
curl -X POST 'localhost:8104/sharedsub/consumers?group=ingest&topic=telemetry/+/data&n=4&qos=1'

# Produce 5000 messages
curl -X POST 'localhost:8104/sharedsub/produce?topic=telemetry/dev01/data&count=5000&qos=1'

# Check distribution - should be roughly even with strategy=random
curl 'localhost:8104/sharedsub/distribution'
# {"g-ingest-c0": 1248, "g-ingest-c1": 1267, "g-ingest-c2": 1234, "g-ingest-c3": 1251}
```

## Production guidance

- **One topic, many groups** is the pattern. Don't create one shared filter per consumer — let the broker do the spread.
- **Persistent sessions are required** if your consumer can disconnect and you don't want to lose its slice of inflight. Use `cleanStart=false` + `sessionExpiryInterval` (POC 09).
- **No replay**: when a slow consumer drops, its assigned messages either go to another consumer (good) or get dropped (bad — depends on QoS). At-least-once + idempotent processing is the standard combo.
- **Wildcard sharing**: `$share/g/telemetry/+/data` is fine; the broker spreads matched messages across the group.
- **Per-group ACL** (POC 05): ACLs apply to the *resolved* topic, not the `$share/...` prefix. The ACL rule for the consumer must allow the underlying filter.

## Slow consumer caveat — see POC 10

Shared subscriptions don't save you from a slow consumer dragging down the others' delivery if they share inflight backpressure. Tune `max_inflight` and `max_mqueue_len` per-listener and use the per-consumer dispatch metrics in Grafana.
