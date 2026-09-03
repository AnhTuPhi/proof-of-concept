# ISSUE — What problem is this repo solving?

> One sentence: **MQTT looks trivial in a demo and breaks in ugly, non-obvious ways at fleet scale — this repo isolates each break, reproduces it, and ships the fix + the trade-off.**

---

## 1. The core problem

A "hello world" MQTT app — connect, subscribe, publish — takes ten minutes and works
forever on a laptop. That success is misleading. The moment you go from **1 device to
1 million devices**, from **1 broker to a cluster**, and from **a happy network to
cellular + rolling deploys + partitions**, a completely different set of failure modes
appears. None of them show up in the tutorial. All of them show up in production, usually
at 3am, usually as a pager alert that reads "the cluster looks fine but messages aren't
flowing."

The knowledge to avoid these failures exists — scattered across the EMQX tuning guide,
the MQTT 5 spec, the AWS architecture blog, and a hundred post-mortems. It is almost never
in one place, and it is almost never **executable**. You cannot `curl` a blog post to see
a connection storm collapse a broker.

**This repo turns that tribal knowledge into 14 runnable proofs.** Each POC:

1. Names one specific failure mode that bites real deployments.
2. Reproduces it on purpose (or makes it measurable).
3. Ships the fix.
4. States the trade-off the fix costs you — because every fix costs something.

---

## 2. Who has this problem

Any team running a **connected-device platform** on MQTT:

- Connected car / telematics (100k+ persistent sessions, cellular flap, OTA).
- Smart home / consumer IoT (millions of cheap devices, session churn, presence).
- Industrial / SCADA (Sparkplug B, liveness, deterministic routing).
- Any backend that ingests device telemetry and has to *not fall over* when the fleet
  reconnects all at once.

If you have fewer than a few thousand devices and a single broker, most of this repo is
"good to know." Past that, each POC is a landmine you *will* step on.

---

## 3. The specific failure modes (the "issue list")

Each row is a real incident pattern. The POC column is where it is reproduced and fixed.

| # | The failure mode | Why it is non-obvious | POC |
|---|---|---|---|
| 1 | One JVM / one broker silently caps out far below "a million" | The wall is not RAM — it's `ulimit -n`, ephemeral ports, TCP backlog, and Erlang scheduler binding, hit *in that order* | [01](01-million-connections/README.md) |
| 2 | A broker restart takes down the *surviving* brokers | Every client reconnects with the **same** timing → thundering herd; the fix (jitter) is counter-intuitive because "exponential backoff" alone still synchronizes | [02](02-connection-storm/README.md) |
| 3 | Teams pick QoS 2 "to be safe" and throughput collapses ~10× | "Exactly once" *sounds* strictly better; nobody measures the 4-step handshake cost until it's in prod | [03](03-qos-levels/README.md) |
| 4 | Backend consumers can't scale horizontally behind the broker | Naïve fan-out delivers every message to every consumer; the broker-side `$share/` group is the fix but changes ordering + replay semantics | [04](04-shared-subscriptions/README.md) |
| 5 | "Password auth" with one shared secret per fleet | Rotate one device, rotate them all; no real per-device identity; cross-tenant leaks by default | [05](05-auth-jwt-mtls/README.md) |
| 6 | An extra microservice just to move MQTT → Kafka/DB | It's a service to run, scale, monitor — and it adds latency the broker's own rule engine wouldn't | [06](06-rule-engine-kafka-bridge/README.md) |
| 7 | Stuck on MQTT 3.1.1 → every failure looks identical on the wire | No reason codes, no user properties, no topic alias, no session-expiry — you're debugging blind | [07](07-mqtt5-features/README.md) |
| 8 | Backend thinks dead devices are alive for 45+ seconds | Presence-by-keepalive-timeout is slow and false-positives on cellular flap; LWT + will-delay is the fix | [08](08-lwt-presence/README.md) |
| 9 | Millions of orphan "zombie" sessions eat broker RAM | `cleanSession=false` + random clientId = a session leaked per boot, kept forever | [09](09-session-persistence/README.md) |
| 10 | Retained messages with no TTL swap the broker to death | 50M unique topics × N bytes, all kept forever, silently — until OOM | [10](10-retained-messages/README.md) |
| 11 | "We need AWS IoT Device Shadow but on our own broker" | The broker is fire-and-forget; desired-state for offline devices needs durable state off the broker | [11](11-device-shadow/README.md) |
| 12 | Every team invents its own JSON payload + has no liveness signal | Industrial/SCADA needs self-describing payloads and BIRTH/DEATH; DIY JSON doesn't provide either | [12](12-sparkplug-b/README.md) |
| 13 | Pushing a 1MB firmware to 1M devices at once melts the broker | 1M × 1MB = 1TB of broker bytes + backpressure death; naïve push has no resume, no integrity | [13](13-ota-updates/README.md) |
| 14 | "The cluster is up but messages aren't routing" | Split-brain: two halves each think they're the cluster; even-sized clusters heal non-deterministically | [14](14-cluster-split-brain/README.md) |

See the fuller "gotchas cheat sheet" in the [README](README.md#the-gotchas-cheat-sheet).

---

## 4. What is explicitly *not* the issue here

To keep each POC honest and focused, the following are **out of scope** (see the README's
"deliberately NOT in this repo" section):

- HTTPS/OAuth API gateway concerns (upstream of MQTT).
- Time-series DB tuning (POCs sink to Postgres for readability, not throughput).
- Production Sparkplug via Eclipse Tahu (POC 12 hand-rolls to teach the spec).
- A full integration-test pyramid (Testcontainers is wired; per-POC tests are stubs).

---

## 5. Success criteria

This repo has done its job if a reader can:

1. **Reproduce** each failure mode on their own machine (`./scripts/up.sh`, then run the POC).
2. **See** the fix change the outcome (throughput number, Grafana panel, `curl` response).
3. **Explain the trade-off** the fix imposes — because shipping the fix blindly creates the
   next incident.
4. **Carry the pattern** into their own platform with the production checklist each POC ships.

For *how* each of these is solved (solution shape, key tech, tech debt), see
[TECHNICAL.md](TECHNICAL.md). For *how it behaves when you scale the consuming apps on
Kubernetes or VMs*, see [CONSISTENCY.md](CONSISTENCY.md).
