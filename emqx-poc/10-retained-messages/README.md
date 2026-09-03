# POC 10 — Retained Messages + The Cleanup Trap

> **Goal:** Show what retained messages are useful for, and the silent broker-killer when teams use them carelessly.

## What retained does

A `PUBLISH` with `retain=true` does TWO things:
1. **Delivers normally** to current subscribers.
2. **Stored on the broker**: next subscriber to that topic gets the last retained message **immediately** on subscribe.

The broker keeps exactly **one retained message per topic**. Publishing a new retained message replaces the previous one. **Publishing a zero-byte retained message deletes it.**

## The killer use cases

- **Current state**: device presence (POC 08), current config, current desired state (POC 11), current alarm state.
- **Late-joiner pattern**: backend service restarts, subscribes, immediately sees all current device states without waiting.
- **Schema announce**: device publishes retained `{"schema":"v3","payload_format":"json"}` to a control topic; new clients subscribe and learn the schema.

## The trap: retained messages have NO default TTL in MQTT 3.1.1

The single most common EMQX outage that isn't a connection storm:

```
Device publishes retained on every state change
  topic: state/{deviceId}/{sensorId}
  device fleet: 1M devices × 50 sensors = 50M unique topics
  state changes per device: ~10/day
  no TTL → all 50M retained messages live forever
  broker retainer table → 50M entries × ~256B = 12GB RAM
  broker swaps, dies, page operations 8x slower
```

## The fix: MQTT 5 per-message expiry

```java
MqttMessage m = new MqttMessage(payload);
m.setRetained(true);
MqttProperties p = new MqttProperties();
p.setMessageExpiryInterval(3600);   // 1 hour
m.setProperties(p);
```

Plus broker-wide safety net:
```hocon
retainer.msg_expiry_interval = "24h"   # max TTL, applied if none set
retainer.max_retained_messages = 1000000
```

Either alone is risky. Belt-and-braces.

## Run

```bash
# Set a retained message with 1-hour TTL
curl -X POST 'localhost:8110/retained/set?topic=state/dev1/temp&payload=22.5&ttl=3600'

# Subscribe to see it
mosquitto_sub -h localhost -p 1883 -u backend-svc -P backend-secret -t 'state/dev1/temp' -v
# (immediately receives: state/dev1/temp 22.5)

# Clear it
curl -X POST 'localhost:8110/retained/clear?topic=state/dev1/temp'

# The trap: bulk-create retained without TTL
curl -X POST 'localhost:8110/retained/spam?prefix=oops/dev&n=10000'
# Check EMQX dashboard - retainer messages now at 10000+
```

## Cleanup strategies for legacy retained pollution

If you inherit a broker with millions of stale retained messages:

1. **Bulk clear via topic walk + empty publish**:
   ```bash
   mosquitto_sub -h broker -t '#' -F "%t" --retained-only -W 60 | \
   while read t; do mosquitto_pub -h broker -t "$t" -r -n; done
   ```
   `-n` = null payload = delete.

2. **EMQX `emqx_ctl retainer clean`** for nuclear option.

3. **Rule engine catch-all**: filter on retained=true and republish without retain if older than 24h. Doesn't work cleanly — better to set TTL going forward.

## Best practices for new code

1. **Always set `messageExpiryInterval`** when publishing retained.
2. **Use a hierarchy with bounded topic counts**, e.g. `state/{deviceId}` not `state/{deviceId}/{sensorId}/{timestamp}` — the latter has unbounded cardinality.
3. **One retained message per logical entity**, not per event.
4. **For event streams**, use a non-retained topic + a queue/db. Retained is for *state*, not history.
5. **Periodic sweep** in your provisioning service: walk known device IDs, delete retained on retired devices.

## Wildcard subscribe gotcha

A `mosquitto_sub -t '#'` from a debug tool will receive **every retained message** at subscribe time. On a broker with millions, the client floods, the broker queues, and other clients see backpressure. **Never debug-subscribe to `#`** on a production broker. Use specific topic patterns.
