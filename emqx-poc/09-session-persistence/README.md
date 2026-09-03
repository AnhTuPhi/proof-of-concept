# POC 09 — Session Persistence (Clean vs Persistent)

> **Goal:** Show what the broker actually holds for you between disconnects, and the gotchas that cause "5 million zombie sessions" in production.

## The four (cleanStart × sessionExpiry) cases

| cleanStart | sessionExpiry | Behavior | When to use |
|---|---|---|---|
| `true` | `0` | Stateless; broker forgets immediately on disconnect | Backend services, ephemeral tools |
| `true` | `>0` | Start fresh, but if you disconnect, broker holds your subs for N seconds | Rolling-restart-tolerant backends |
| `false` | `0` | Resume previous session if exists, then expire immediately | Quirky; rarely useful |
| `false` | `>0` | Persistent session — QoS 1/2 messages queued while offline | **The pattern for devices that disconnect frequently** |

## What "the broker holds" actually means

When you have a persistent session:

1. **Subscriptions** — the broker remembers what topics you're subscribed to. You don't have to re-subscribe on reconnect.
2. **Inflight QoS 1/2 messages** — messages mid-handshake at disconnect resume on reconnect.
3. **Queued messages** — for QoS 1/2 on subscribed topics that arrived while you were offline (up to `max_mqueue_len`).

QoS 0 is **never queued**, even with a persistent session. If you need at-least-once for a device that goes offline, use QoS 1.

## Run

```bash
# 1. Connect as device-A with persistent session, subscribe, then disconnect
curl -X POST 'localhost:8109/session/connect?clientId=device-A&cleanStart=false&sessionExpiry=3600'
# {"sessionPresent":false, ...}    <-- first connect; nothing held yet

curl -X POST 'localhost:8109/session/disconnect?clientId=device-A'

# 2. While device-A is offline, backend publishes 10 messages
curl -X POST 'localhost:8109/session/publish-while-offline?clientId=device-A&count=10'

# 3. device-A reconnects with same clientId, cleanStart=false
curl -X POST 'localhost:8109/session/connect?clientId=device-A&cleanStart=false&sessionExpiry=3600'
# {"sessionPresent":true, "receivedSoFar":...}   <-- broker resumes our session

sleep 1
curl 'localhost:8109/session/received?clientId=device-A'
# {"clientId":"device-A","totalReceived":10}   <-- the queued msgs came through

# 4. Now try cleanStart=true on reconnect - SESSION WIPED, queued messages LOST
curl -X POST 'localhost:8109/session/disconnect?clientId=device-A'
curl -X POST 'localhost:8109/session/publish-while-offline?clientId=device-A&count=10'
curl -X POST 'localhost:8109/session/connect?clientId=device-A&cleanStart=true&sessionExpiry=0'
# receivedSoFar will NOT increment - the cleanStart=true threw the session away
```

## The "session table explosion" trap

The original sin: **`cleanSession=false` with millions of unique client IDs**.

What used to happen with MQTT 3:
```
Device boots, generates random clientId, connects with cleanSession=false
Device goes offline, never returns (factory reset, decommissioned, dead battery)
Broker keeps the session FOREVER
Multiply by 1M devices over 2 years -> broker session table at 730M entries
Broker OOM
```

MQTT 5 fixed this by **requiring** a session expiry. Set it. Without it, the broker won't accept your connect.

Recommended values:
- **High-availability industrial sensors**: 24h (long enough for a maintenance window)
- **Consumer IoT (smart home)**: 2h
- **Mobile devices**: 30m
- **Backend services**: 0 (stateless)

## ClientID hygiene

The clientId determines whose session you resume. For devices:
- **Use a stable identifier** (MAC, serial number, device certificate fingerprint).
- **Never generate a random clientId** on persistent sessions — you'll never find your session again.

For backend services using shared subscriptions (POC 04):
- **Use a deterministic clientId per pod** (`pod-name`, `hostname`-derived).
- This lets the broker resume your slice of the shared subscription on pod restart.

## Sticky load balancing matters

If you have a cluster behind a load balancer (HAProxy `balance source` in our docker-compose) and your device reconnects to a *different* node:
- The broker fetches your session state from Mria (the broker-side replicated DB)
- That's fine but it's a cross-node call — slower under heavy churn

In our docker-compose, HAProxy is configured with `balance source` so the same client IP keeps hitting the same node. This is the standard configuration.

## How to tell if a client got their persistent session back

Look at the **`sessionPresent`** field on CONNACK. The Paho client exposes it as `IMqttToken#getSessionPresent()`. If true, the broker resumed your subs; if false, you're starting fresh (and you should re-subscribe).

In production: log this on every connect. A sudden drop in `sessionPresent=true` indicates a session-store issue on the broker (e.g. Mria sync problems, node was wiped, expiry too short).
