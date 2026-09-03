# POC 11 — Device Shadow on EMQX (AWS IoT-style)

> **Goal:** Build the AWS IoT Device Shadow pattern on top of vanilla EMQX. Three pieces of state — `reported`, `desired`, `delta` — converge devices to backend intent over an intermittent link.

## Why a shadow?

The MQTT broker is fire-and-forget. If the backend says "turn the heater off" while the device is offline, the message dies — even with QoS 1, if the device session expired, the queued PUBLISH is gone.

A **shadow** moves the source-of-truth off the broker and into a database. The pattern:

1. **Device** publishes its current state to `$devices/{id}/shadow/update` whenever it changes.
2. **Shadow service** stores it in Postgres as `reported` JSONB.
3. **Backend** writes the wanted state via REST. Shadow service stores it as `desired` JSONB.
4. **Delta** = `desired` − `reported`, computed every time either side changes.
5. **Shadow service** publishes the delta to `$devices/{id}/shadow/update/delta` with `retain=true`. Device subscribes — even if it joins a week later, it gets the most recent delta immediately.
6. **Device** acts on delta, publishes new `reported`. Loop closes; delta clears.

## Topic convention

We mirror AWS IoT so devices written against AWS-style SDKs work unchanged:

```
$devices/{deviceId}/shadow/update          ← device publishes reported state
$devices/{deviceId}/shadow/get             ← device requests current shadow
$devices/{deviceId}/shadow/get/accepted    ← shadow publishes snapshot (response)
$devices/{deviceId}/shadow/update/delta    ← shadow publishes delta (retained)
```

`$devices/` is a tenant-local namespace; the EMQX ACL allows it explicitly for any authenticated device. In production you'd scope it to `$devices/{tenant}/{deviceId}/...`.

## Why Postgres (not Redis, not in-memory)

The shadow MUST survive broker restarts. Retained messages also survive but:
- Retained replaces wholesale — no JSON-level merge.
- No history of who wrote what when.
- No transactional invariants ("desired temp 18°C only if mode = heat").

JSONB + the `||` operator gives us shallow merge for free:

```sql
DO UPDATE SET reported = device_state.reported || EXCLUDED.reported
```

A device that reports `{"battery": 80}` doesn't wipe `{"firmware": "v1.2.3"}` set earlier. **Shallow only** — nested objects get replaced, not deep-merged. AWS IoT has the same semantics.

## The delta algorithm

This POC computes the delta key-by-key over the top level:

```java
for (var e : desired.entrySet()) {
    Object r = reported.get(e.getKey());
    if (r == null || !r.equals(e.getValue())) delta.put(e.getKey(), e.getValue());
}
```

It's intentionally simple — `delta` carries exactly the desired keys that don't match reported. **Caveat:** this ignores keys present in reported but absent from desired. That's correct for the shadow pattern: the backend doesn't manage state it doesn't claim.

For nested JSON (e.g. `{"thermostat": {"target": 21, "mode": "heat"}}`) the comparison is by-value at the top key. A 1-byte change to anything under `thermostat` re-sends the whole subtree. This matches AWS IoT.

## The retained-delta trap

Delta is published with `retain=true` so devices that connect later get the most recent unresolved intent. This is a deliberate retained message. Two things to remember:

1. **Clear it once reported catches up.** This POC publishes a zero-byte retained message to `…/update/delta` once `delta = {}`. Otherwise the device keeps getting a stale delta on every reconnect.
2. **Set TTL.** This POC sets `messageExpiryInterval=86400` (24h) following POC 10. If a device is offline for a week, an old delta we forgot to clear shouldn't haunt the broker's retainer table forever.

## Session expiry on the shadow service itself

The shadow service connects with `cleanStart=false` and `sessionExpiryInterval=86400`. **Reason:** if the shadow service restarts (deploy, OOM), devices that published `shadow/update` while it was down must have those messages queued by the broker for delivery on reconnect. Without persistent session, those state changes vanish silently and the delta drifts from reality. POC 09 covers this in detail.

## Run

```bash
# Subscribe as device-001 to see deltas
mosquitto_sub -h localhost -p 1883 -u device-001 -P device-001-secret \
  -t '$devices/device-001/shadow/update/delta' -v &

# Backend asks for thermostat=22, mode=heat
curl -X POST 'localhost:8111/shadow/desired?deviceId=device-001' \
  -H 'Content-Type: application/json' \
  -d '{"thermostat": 22, "mode": "heat"}'
# → mosquitto_sub immediately receives: {"thermostat":22,"mode":"heat"}

# Device reports it's at 18°C
mosquitto_pub -h localhost -p 1883 -u device-001 -P device-001-secret \
  -t '$devices/device-001/shadow/update' \
  -m '{"thermostat": 18, "mode": "heat"}'
# → mode now matches, delta narrows to: {"thermostat":22}

# Device fully converges
mosquitto_pub -h localhost -p 1883 -u device-001 -P device-001-secret \
  -t '$devices/device-001/shadow/update' \
  -m '{"thermostat": 22}'
# → delta cleared (empty retained message published)

curl 'localhost:8111/shadow/snapshot?deviceId=device-001' | jq
```

## Production hardening (not in this POC)

- **Optimistic concurrency**: `device_state.version` exists but isn't enforced. Add `WHERE version = :expectedVersion` to detect concurrent updates.
- **Per-tenant isolation**: scope the topic and ACL by tenant (`$devices/{tenant}/{id}/...`).
- **Delta diff for arrays**: AWS uses JSON Patch (RFC 6902) for arrays. Our shallow diff just replaces them.
- **Schema validation**: reject `setDesired` calls that don't match a JSON Schema per device class.
- **Audit trail**: every `setDesired` should write to `event_log` with who/when. Skipped here.
- **Acked-delta tracking**: device should ack the delta with a correlation id so the shadow can prove convergence.
- **Backpressure**: if `shadow/update` traffic exceeds Postgres write throughput, the Paho receive thread blocks. In prod, drain to a queue and ack from a worker pool.

## Differences from AWS IoT Device Shadow

| AWS IoT                          | This POC                  |
|----------------------------------|---------------------------|
| `$aws/things/{name}/...`         | `$devices/{deviceId}/...` |
| `update/documents` event         | not implemented           |
| Classic + named shadows          | classic only              |
| JSON Patch on arrays             | shallow replace           |
| Delta is NOT retained            | we retain it (deliberate) |
| Version conflict → REJECTED      | last-write-wins           |

The retained-delta choice is intentional — without it, devices coming online see no intent until the next `setDesired` call. AWS works around this by having devices issue `shadow/get` on every reconnect, which is more chatty.
