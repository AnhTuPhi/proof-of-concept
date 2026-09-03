# POC 03 — QoS Levels Side-by-Side

> **Goal:** Make the QoS tradeoff visible. Most teams pick QoS 2 "to be safe" and watch their throughput collapse without knowing why.

## What each QoS actually does

| QoS | Hops | Guarantees | When the broker forgets the message |
|---|---|---|---|
| **0** "at most once" | 1 (PUBLISH) | None. Fire and forget. | Immediately. If the TCP buffer is full, dropped. |
| **1** "at least once" | 2 (PUBLISH → PUBACK) | Delivered at least once. Receiver **may see duplicates** on retransmit. | After PUBACK from receiver. |
| **2** "exactly once" | 4 (PUBLISH → PUBREC → PUBREL → PUBCOMP) | Delivered exactly once. | After PUBCOMP. Broker stores message ID until then. |

QoS 2 cost = **4× the round-trips of QoS 0**, plus the broker keeps an in-memory record per inflight message per subscriber. Multiply that by 1M devices and a slow consumer (POC 04) and your broker heap dies.

## Run

```bash
curl 'localhost:8103/qos/benchmark?count=20000&payload=512'
```

Sample output (on a laptop, EMQX in Docker, single-broker):

```json
{
  "qos0": { "qos":0, "delivered":20000, "duplicates":0, "avgLatencyMs": 0.8, "throughputMsgsPerSec": 42000 },
  "qos1": { "qos":1, "delivered":20000, "duplicates":12, "avgLatencyMs": 2.4, "throughputMsgsPerSec": 18500 },
  "qos2": { "qos":2, "delivered":20000, "duplicates":0,  "avgLatencyMs": 6.1, "throughputMsgsPerSec":  6200 }
}
```

The actual numbers vary, but the **ratio** is stable: **QoS 0 ≫ QoS 1 ≫ QoS 2** with a usually 2-3× drop per level.

## Why QoS 1 leaks duplicates and QoS 2 doesn't

QoS 1 retransmits the PUBLISH if the publisher's PUBACK is delayed. The broker can deliver the retransmitted PUBLISH to the subscriber even though the original already went through — that's why receivers must be idempotent.

QoS 2 uses a 4-step handshake with an exchanged packet ID. Both ends track which IDs they've already acknowledged. The broker dedupes before delivering. This is what costs the throughput.

## Production guidance

Default to **QoS 1 + idempotency at the application layer**. Use:
- Sequence numbers (POC includes one) so receivers detect duplicates.
- Idempotent processing (UPSERT, not INSERT) downstream.

Use QoS 2 only when:
- The payload is a financial transaction or command with no idempotency key, AND
- You cannot add idempotency at the application layer.

Use QoS 0 for high-rate telemetry where losing 1 in 10k samples is fine. Most temperature sensors fit here.

## Bonus: QoS downgrade

When the subscriber subscribes with a lower QoS than the publisher publishes with, the **effective QoS = min(pub, sub)**. So a subscriber can opt out of QoS 2 cost on a topic. Use this to let internal services subscribe at QoS 1 even when devices publish QoS 2.
