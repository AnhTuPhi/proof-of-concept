# POC 13 — OTA Firmware Updates over MQTT

> **Goal:** Deliver firmware to a fleet of intermittently-connected devices using only MQTT, with resumability, integrity verification, atomic apply, and the ability to roll a campaign forward or back from the backend.

## Why MQTT for OTA at all

The orthodox answer is *don't* — point devices at HTTPS / S3 and use MQTT only for the "go fetch X" trigger. That's correct when devices have spare bandwidth and reliable HTTPS.

But on cellular, behind enterprise firewalls, or with constrained microcontrollers, MQTT-over-TLS is often the *only* path out. So you do OTA over the same broker, and you pay attention to the constraints below.

## Topic structure

```
ota/{deviceClass}/offer      ← server, retained, "this is the current firmware"
ota/{deviceId}/request       ← device asks for a range of chunks
ota/{deviceId}/chunk/{N}     ← server's reply, one chunk per topic
ota/{deviceId}/status        ← device's progress / state machine
```

The `offer` is retained (POC 10 pattern, with 30-day TTL): a device joining the network months later still sees what firmware it's expected to be running. The `chunk` topics are NOT retained — they exist exactly long enough to deliver and ack.

## The chunked-pull pattern

The server does NOT push the whole image. The device asks for chunks `from..to` and the server replies with that range. Then the device asks for the next window. This gets us four things at once:

1. **Backpressure**: a slow device sets its own pace; the server isn't queueing 5MB per device.
2. **Resumability**: after a crash, the device knows which chunks it has (bitmap) and asks for the rest.
3. **Stagger**: 1M devices waking up at random offsets hash naturally into a rollout curve.
4. **Per-chunk QoS 1 retries**: a packet loss costs you 4KB of retransmit, not the entire 1MB image.

```
device                      server
  | ota/{class}/offer (retained, ?? bytes)
  |<------ {version, sha, totalChunks, chunkSize}
  |
  | ota/{id}/request {from:0, to:15}
  |--------------------------------->
  | ota/{id}/chunk/0..15
  |<----------------------------- ×16 each ≤4KB
  | ota/{id}/status {downloading, 12%}
  |--------------------------------->
  | ota/{id}/request {from:16, to:23}
  |--------------------------------->
  ...
  | ota/{id}/status {verifying}      (SHA-256 check)
  | ota/{id}/status {applied, 100%}
```

## Chunk size — why 4KB

EMQX's default `mqtt.max_packet_size` is 1MB; the WAN MTU is far smaller. Empirically:

- 1KB chunks → too many MQTT round-trips, broker CPU bound
- 4KB chunks → sweet spot for cellular/LoRa
- 16KB chunks → fine on WiFi/Ethernet but cellular re-transmits dominate
- 64KB+ → a single dropped IP packet costs the whole chunk

This POC defaults to 4KB. Make it a campaign parameter — fleets with 5G can run larger.

## Integrity — SHA-256 before apply

The device buffers the image (in this POC, in memory; on a real MCU, in the inactive flash slot), then computes SHA-256 and compares to the offer's hash. **Only then** does it switch the active slot.

This guards against:
- A chunk being corrupted in flight despite TCP checksum (rare but happens).
- A campaign being mid-replaced when the device was downloading (the SHA still matches the version it requested).
- A malicious party slipping a chunk past the broker (defense-in-depth — TLS should already prevent this).

What this POC does NOT do, but production should:
- **Signature, not just hash**. Sign the image with an offline key; bake the public key into the bootloader. SHA alone proves integrity, not authenticity. If the broker is compromised, the attacker can serve their own image with a matching SHA.

## Atomic apply / rollback

Two slots, A and B. Active boots from A. Update writes to B. Verify SHA. Bootloader flips to B. On next boot, if anything is wrong, fall back to A.

This POC fakes the slots by storing `activeVersion` in memory. Real devices use an A/B partition layout — ESP32-IDF, Zephyr, FreeRTOS all have OTA libraries that do this.

## Backpressure on the server

The server's Paho client subscribes with QoS 1. Every published chunk is acked. If 1000 devices all download the same campaign in parallel, you're publishing tens of thousands of in-flight messages.

Levers:
- `max-inflight` on the server side (raised to 500 in `application.yml`).
- **Stagger device wakeup**: include a `cohort` field in the offer and have device i wait `cohort_delay × (deviceId.hashCode() % cohorts)` before requesting. Linear rollout.
- **Move chunks off MQTT entirely**: offer a pre-signed S3 URL in the offer payload and let MQTT just trigger and report.

## Run

```bash
# Publish a 256KB / 4KB-chunks firmware
curl -X POST 'localhost:8113/ota/campaign?targetClass=thermostat-v2&version=v1.4.0&sizeKb=256'

# Connect three devices of that class
curl -X POST 'localhost:8113/ota/device?deviceId=thermo-001&targetClass=thermostat-v2'
curl -X POST 'localhost:8113/ota/device?deviceId=thermo-002&targetClass=thermostat-v2'
curl -X POST 'localhost:8113/ota/device?deviceId=thermo-003&targetClass=thermostat-v2'

# Watch progress
watch -n1 "curl -s localhost:8113/ota/status | jq"

# Cancel mid-flight
curl -X POST 'localhost:8113/ota/cancel?targetClass=thermostat-v2'

# Roll forward to a new version
curl -X POST 'localhost:8113/ota/campaign?targetClass=thermostat-v2&version=v1.4.1&sizeKb=256'
```

## What's not in this POC

- **Signature verification.** Hash-only.
- **Device-side persistent bitmap** — restart wipes the progress.
- **Cohort-based rollout** — all devices grab as fast as they can.
- **Delta updates** — full image only. bsdiff / xdelta over MQTT is a known pattern but a whole second POC.
- **Per-tenant ACL on `ota/+/...`** — the multi-tenant story from POC 05 applies but is omitted here for brevity.

## EMQX-specific notes

- The `chunk/{N}` topic uses `N` as a level — keeps the wildcard subscription `ota/{deviceId}/chunk/+` clean. Don't shove `N` into a Kafka-like single topic; you lose the QoS-1 per-chunk semantics.
- For large fleets, run OTA against a dedicated EMQX listener (e.g. `:1883` for telemetry, `:1884` for OTA) with separate resource limits. A misbehaving campaign should not impact device telemetry.
- EMQX Rule Engine can emit `$events/message_delivered` events you can sink to Postgres for per-chunk audit. We don't wire that here.
