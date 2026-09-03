# POC 08 — LWT (Last Will & Testament) for Presence

> **Goal:** Show the MQTT-native way to do device-online/offline detection — without polling, without an extra service. **The MQTT 5 will-delay interval is what makes this finally robust against flaky cellular.**

## The pattern

```
Device connects:
  Will-topic   = presence/{deviceId}
  Will-payload = {"status":"offline","ts":...}
  Will-retain  = true
  Will-delay   = 5s     <-- MQTT 5 only; suppresses false-offline on brief reconnects

Device publishes (RETAINED) after connect:
  presence/{deviceId} = {"status":"online","ts":...}

Backend subscribes to presence/+
```

| Event | What happens |
|---|---|
| Device boots, connects, publishes "online" | Backend sees `online` |
| Device disconnects cleanly (sends DISCONNECT packet) | LWT does **not** fire. App should publish "offline" itself. |
| Device's network drops (TCP RST or keepalive timeout) | Broker waits `willDelay` seconds. If device reconnects, will is **suppressed**. Otherwise broker publishes the will → backend sees `offline`. |
| New backend subscribes | Sees the retained current state immediately (no need to wait for next change). |

## Run

```bash
curl -X POST 'localhost:8108/lwt/spawn?deviceId=dev-001&keepAlive=30&willDelay=5'
# {"deviceId":"dev-001","keepAlive":30,"willDelay":5}

curl 'localhost:8108/lwt/presence'
# {"dev-001":"{\"status\":\"online\",\"ts\":\"...\"}"}

# Graceful: LWT NOT fired. App publishes offline itself.
curl -X POST 'localhost:8108/lwt/graceful?deviceId=dev-001'

# Hard kill: simulates network drop. After willDelay=5s, broker fires will.
curl -X POST 'localhost:8108/lwt/spawn?deviceId=dev-002&keepAlive=30&willDelay=5'
curl -X POST 'localhost:8108/lwt/kill?deviceId=dev-002'
sleep 6
curl 'localhost:8108/lwt/presence'
# {"dev-002":"{\"status\":\"offline\",\"reason\":\"lwt-fired\",...}"}

# Recent events
curl 'localhost:8108/lwt/events'
```

## Why the will delay matters (the MQTT 3 problem)

In MQTT 3.1.1, the will fires immediately on any disconnect. With flaky cellular:
- Device loses network for 2 seconds
- Broker fires will → backend marks device offline
- Device reconnects 3 seconds later → backend marks online
- This happens every few minutes → false alarms, flapping dashboards

In MQTT 5 with `willDelayInterval=5s`:
- Device disconnects → broker starts a 5s timer
- Device reconnects within 5s → timer cancelled, no will fires
- Devices that are actually gone trigger the will

**Tune willDelay to your network**: 5-10s for cellular, 30-60s for satellite, 1-2s for WiFi.

## Keep-alive interaction

The broker detects a hard disconnect when it doesn't receive a PINGREQ within `1.5 * keepAliveInterval`. So with `keepAlive=30`, max detection lag is ~45s. Add `willDelay=5`, total worst-case until backend sees offline: ~50s.

Tradeoffs:
- Shorter keep-alive = faster detection, more PING traffic (negligible at 30s; significant at 5s × 1M devices)
- Shorter will delay = more false offlines on flaky links

## Why retained matters here

Without `retain=true` on the will:
- Backend subscribes
- Device has been offline for a week
- Backend has no idea — the offline event was delivered to subscribers a week ago and dropped

With `retain=true`:
- Backend subscribes  
- Backend instantly receives the current state (last published value)
- Memory cost: one retained message per device. At 1M devices = ~50MB on the broker. Tune `retainer.msg_expiry_interval` so dead devices clean up.

## Production patterns built on top of LWT

1. **Disconnect notifications**: rule engine catches `presence/+` `status=offline`, fans out to PagerDuty/Slack.
2. **Geo-tracking with last-seen**: store presence events to Postgres with timestamp.
3. **Conditional command delivery**: backend checks presence retained-message before sending commands; if offline, queues to a database for next connect.
4. **Fleet health metric**: count of devices with `online` status, scraped by Prometheus from a rule-engine HTTP sink.

## Don't reinvent presence with periodic polls

A common antipattern: backend pings each device every 60s. At 1M devices that's 16k publishes/sec just for heartbeats, plus the same volume of replies. LWT gives the same information for free, with no message volume.
