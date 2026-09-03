# EMQX Production Patterns — POCs

> Production-grade demos of the patterns that break in real EMQX/MQTT deployments. Built with **Spring Boot 3.4**, **Java 21**, **EMQX 5**, and the **Paho MQTT v5** + **HiveMQ MQTT** clients. Each POC isolates ONE failure mode, includes the fix, and explains the trade-off.

This is a learning repo. The goal is not "another MQTT chat demo." Every POC matches a specific bullet from the "things that go wrong at scale" list that ops teams keep rediscovering.

---

## Documentation map

Start here depending on what you need:

| Doc | Read it for |
|-----|-------------|
| [ISSUE.md](ISSUE.md) | **What problem this repo solves** — the 14 failure modes, who hits them, success criteria |
| [TECHNICAL.md](TECHNICAL.md) | **Per-POC deep dive** — hard problem, what we protect, solution shape, key tech by responsibility, sub-problem breakdown, tech debt |
| [CONSISTENCY.md](CONSISTENCY.md) | **Scaling on k8s / VMs** — clientId collisions, shared-sub fan-out, sticky routing, split-brain during rolling deploys |
| [demo.html](demo.html) | **Interactive explainer** — architecture flow, clickable POC cards, and a live connection-storm simulation. Open in any browser (no server needed) |
| This README | TL;DR, run instructions, gotchas cheat sheet, production hygiene notes |

---

## TL;DR

```bash
# 0. Bring up infra (3-node EMQX cluster, HAProxy, Postgres, Kafka, Prometheus, Grafana)
./scripts/up.sh

# 1. Build every POC
./scripts/build.sh

# 2. Run any one POC, e.g. POC 03 - QoS Levels
mvn -pl 03-qos-levels spring-boot:run

# Each POC exposes its REST surface on a port 81NN (NN = poc number).
# EMQX dashboard at http://localhost:18083 (admin / public)
# Grafana       at http://localhost:3000  (admin / admin)
# Prometheus    at http://localhost:9090
```

---

## What's in here

| # | POC | Port | What breaks in prod | What this shows |
|---|-----|------|--------------------|-----------------|
| 01 | [Million Connections](01-million-connections/README.md) | 8101 | Linux fd limits, ephemeral port exhaustion, single-Erlang-VM heap pressure | Ramp 100k+ clients per JVM with HiveMQ shared Netty event loop; paced fleet ramp |
| 02 | [Connection Storm](02-connection-storm/README.md) | 8102 | Synchronized reconnect after a broker bounce kills the next broker | Four reconnect strategies (constant, exponential, full-jitter, decorrelated-jitter); show the storm visually |
| 03 | [QoS Levels](03-qos-levels/README.md) | 8103 | Choosing QoS 2 because "exactly once sounds safe" | Side-by-side throughput / latency / dup-detect on 0 / 1 / 2 |
| 04 | [Shared Subscriptions](04-shared-subscriptions/README.md) | 8104 | Unbalanced consumer load, no per-consumer routing | `$share/group/topic` semantics, distribution counting, group rebalance on consumer death |
| 05 | [Auth: JWT + mTLS](05-auth-jwt-mtls/README.md) | 8105 | "Password auth" with shared secrets, no per-device identity | JWT issue/verify, in-memory BouncyCastle CA, Postgres ACL chain |
| 06 | [Rule Engine + Kafka Bridge](06-rule-engine-kafka-bridge/README.md) | 8106 | Brittle HOCON files for the rule engine, broker-bound business logic | Mgmt-API provisioned rules; telemetry → Kafka; verification consumer |
| 07 | [MQTT 5 Features](07-mqtt5-features/README.md) | 8107 | Stuck on MQTT 3, missing reason codes, no topic alias | Reason codes, user properties, topic alias, request/response over MQTT |
| 08 | [LWT + Presence](08-lwt-presence/README.md) | 8108 | Backend doesn't know a device went offline until keepalive×1.5 elapses | Will-delay-interval, presence subscriber, hard-kill simulator |
| 09 | [Session Persistence](09-session-persistence/README.md) | 8109 | `cleanSession=false` + ever-changing clientIds → orphan sessions everywhere | All 4 cleanStart × sessionExpiry combinations + `sessionPresent` flag |
| 10 | [Retained Messages](10-retained-messages/README.md) | 8110 | 50M retained messages with no TTL → broker swaps & dies | MQTT 5 `messageExpiryInterval` + broker-wide `retainer.msg_expiry_interval` |
| 11 | [Device Shadow](11-device-shadow/README.md) | 8111 | "I need AWS IoT but on EMQX" | desired / reported / delta over Postgres JSONB; AWS-compatible topic convention |
| 12 | [Sparkplug B](12-sparkplug-b/README.md) | 8112 | DIY JSON payloads, no liveness signal for SCADA | Eclipse-spec NBIRTH/NDATA/NDEATH/DBIRTH; protobuf payloads; bdSeq pairing; LWT-as-NDEATH |
| 13 | [OTA Updates](13-ota-updates/README.md) | 8113 | Pushing a 1MB firmware to 1M devices at once → broker meltdown | Chunked pull, SHA-256 verify, resumable, retained offer |
| 14 | [Cluster + Split-brain](14-cluster-split-brain/README.md) | 8114 | "the cluster looks fine but messages aren't routing" | Per-node probe + Mgmt-API membership view + `docker network disconnect` demo |

---

## Architecture

```
                              ┌──────────────────┐
            ┌─── :8101 ───────│ POC 01 fleet     │
            │                 └──────────────────┘
            │                 ┌──────────────────┐
            ├─── :8102 ───────│ POC 02 storm     │
            │                 └──────────────────┘
            │                  …
            │                 ┌──────────────────┐
            ├─── :8114 ───────│ POC 14 probe     │
            │                 └──────────────────┘
            │                                                       ┌─────────┐
            │                          ┌───────────────────┐        │ Postgres│
            │                          │ HAProxy           │        │ :5432   │
            │                          │ :1880 mqtt        │        └─────────┘
   localhost│                          │ :8880 ws          │             ▲
            │  ────────────────────►   └─────┬─────────────┘             │
            │                                │                           │ auth+ACL
            │                                ▼                           │ device_state
            │                  ┌─────────────┴─────────────┐             │ telemetry sink
            │                  │ EMQX 5 cluster (3 cores)  │─────────────┘
            │                  │ emqx1 :1883  :18083       │
            │                  │ emqx2 :1884  :18084       │             ┌─────────┐
            │                  │ emqx3 :1885  :18085       │────────────►│ Kafka   │
            │                  └─────────────┬─────────────┘  rule       │ :9092   │
            │                                │                  engine   └─────────┘
            │                                ▼
            │                  ┌──────────────────────────┐
            └──── :9090 ───────│ Prometheus → Grafana :3k │
                               └──────────────────────────┘
```

- **HAProxy in front of EMQX**: `balance source` for sticky MQTT sessions (otherwise an MQTT 5 cleanStart=false client gets routed to a different node on reconnect and finds no session there). 4-hour TCP timeout matches the keepalive ceiling.
- **3-node EMQX cluster**: core-only, no replicants. Lets us demo cluster routing AND split-brain in 14.
- **Postgres** for everything stateful: auth (`mqtt_user` / `mqtt_acl`), device shadow (`device_state`), rule-engine telemetry (`telemetry`), audit (`event_log`).
- **Kafka** receives `telemetry/+/+/+` via the EMQX rule engine in POC 06.
- **Grafana** preloaded with EMQX + per-POC dashboards (basic — extend at will).

---

## Key implementation decisions

### Paho v5 (single-client POCs) vs HiveMQ (scale POCs)
Paho is the thread-per-client client, easy to reason about, fine up to ~5k connections per JVM. HiveMQ uses a shared Netty event loop and can hold 100k+ connections in one process. POC 01 and 02 use HiveMQ; everything else uses Paho v5.

### MQTT 5, not 3.1.1
POC 07 needs reason codes, user properties, topic alias. POC 08 needs will-delay-interval. POC 09 needs session-expiry-interval. POC 10 needs per-message TTL. None of these exist in MQTT 3. So we default to v5 everywhere; v3 clients still connect, they just don't see the v5 features.

### Mgmt API provisioning over HOCON files
POC 06 provisions Kafka connectors / actions / rules via PUT to `/api/v5`. HOCON works fine for static config but the Mgmt API is hot-reloadable, validated, and works the same in a managed EMQX Cloud cluster. The provisioning code lives in `EmqxRuleProvisioner` and runs at startup.

### Decorrelated jitter for reconnect (POC 02)
The AWS Architecture Blog recipe. Full-jitter is fine but decorrelated gives a smoother distribution — for a 100k client storm it visibly reduces the second-wave thundering herd.

### Multi-tenant pattern
`tenant/{tenant_id}/devices/{device_id}/...` with default-deny ACL (POC 05). Cross-tenant publish/subscribe is implicitly denied. Demo accounts seeded in `infra/postgres/init.sql`.

---

## Running individual POCs

Every POC is a separate Spring Boot app. To run one:

```bash
mvn -pl 03-qos-levels spring-boot:run
```

Most expose REST endpoints under `localhost:81NN/...`. Check each POC's README for examples.

Some scripts in `scripts/` drive specific POCs:

```bash
NUM_CONNS=200000 ./scripts/poc-01-million-conns.sh
STRATEGY=DECORRELATED_JITTER ./scripts/poc-02-storm.sh
./scripts/poc-14-splitbrain.sh
```

---

## The "gotchas" cheat sheet

These are the patterns that bite production deployments. Each is illustrated by at least one POC.

| Gotcha | POC | Why it hurts |
|--------|-----|--------------|
| Synchronized reconnect after a broker bounce | 02 | The "thundering herd" — surviving brokers die under the second wave |
| Choosing QoS 2 for "safety" | 03 | 4-step handshake; throughput ~10× lower than QoS 1 with no real gain |
| `$share/` with stateful consumers | 04 | Messages spread across consumer instances; in-memory state is wrong |
| Password auth in `mqtt_user` table | 05 | Symmetric secret per device; rotate one, rotate them all |
| Rule engine business logic in HOCON | 06 | Untestable, slow to deploy, often diverges from app code |
| MQTT 3.1.1 forever | 07 | No reason codes → all failures look the same on the wire |
| No LWT, no presence topic | 08 | Backend thinks 10k zombie devices are alive for 45+ seconds |
| `cleanSession=false` + random clientId | 09 | Every connect leaks a session; broker memory grows linearly |
| Retained messages without TTL | 10 | 50M unique topics × N MB of state = OOM the retainer |
| Shadow in Redis | 11 | Redis is fast but not durable; lose state on partition |
| DIY industrial payloads | 12 | Every team invents a different metric format; no liveness signal |
| Pushing whole firmware | 13 | 1M devices × 1MB = 1TB of broker bytes; backpressure death |
| 2-node or 4-node cluster | 14 | No majority → split-brain heals non-deterministically |
| Wildcard `#` debug subscribe | 10 | Floods the debug client and backpressures every other client |

---

## Repo layout

```
common/                       shared MqttClientProperties, factory, util
infra/
  emqx/etc/                   listener config, mTLS, JWT+Postgres auth chain
  haproxy/                    balance source, 4h TCP timeout
  postgres/init.sql           auth, ACL, shadow, telemetry, event_log
  prometheus/                 5s scrape config
docker-compose.yml            3 EMQX + HAProxy + PG + Kafka + Prom + Grafana
scripts/                      up / down / build / per-POC helpers
01-million-connections/  …  14-cluster-split-brain/      each POC self-contained
```

Each POC follows the same layout:

```
NN-name/
  pom.xml                                modules off the parent
  src/main/java/com/claude/emqx/<pkg>/
    Application.java                      Spring Boot main
    <Service>.java                        the POC's MQTT work
    <Controller>.java                     REST surface
  src/main/resources/application.yml      port 81NN, mqtt config
  README.md                               what breaks, fix, run instructions
```

---

## Versions

- Spring Boot **3.4.0**
- Java **21**
- Paho MQTT v5 **1.2.5**
- HiveMQ MQTT client **1.3.3**
- Protobuf **3.25.5** (Sparkplug B)
- BouncyCastle **1.78.1** (in-memory X.509 CA for mTLS)
- Resilience4j **2.2.0** (jittered retry)
- EMQX **5.x** (latest at compose time)
- Postgres **16**
- Kafka **3.9**

---

## Production notes that didn't fit into individual POCs

These are general EMQX-operational hygiene items, not POCs per se.

1. **Run an odd cluster size** — 3, 5, or 7. Never 2 or 4. POC 14 explains.
2. **One listener per role**. Telemetry traffic, OTA, fleet-control should each have a dedicated listener (e.g. `:1883`, `:1884`, `:1885`) with separate connection limits. A misbehaving OTA campaign shouldn't take telemetry down with it.
3. **Authentication chain is ordered**. EMQX evaluates `mqtt.authentication` top-down. Put JWT (cheap, in-memory) before Postgres (DB round-trip) so the common path is fast. ACL cache TTL ≥ 60s.
4. **Set `mqtt.max_packet_size`** explicitly. Default is 1MB; that's huge. If your largest legitimate payload is 16KB, cap at 64KB and you reject most accidental abuse with a clean DISCONNECT reason code.
5. **`mqtt.max_topic_levels`** caps topic depth. A misbehaving client building `a/b/c/d/e/.../z` topics is rare but possible — cap at 16 unless you have a reason.
6. **Set `mqtt.retain_available = false`** on listeners where retained makes no sense (e.g. high-frequency telemetry). Defense in depth against POC 10's failure mode.
7. **Enable `listener.tcp.external.send_buffer` and `recv_buffer`** > 64KB if you're seeing slow-consumer warnings. The default Erlang socket buffers are conservative.
8. **Monitor `emqx_message_dropped`** as the primary smoke alarm. It increments for: slow consumers, queue full, packet too large, retained-quota-exceeded.
9. **DO NOT run `emqx_ctl cluster leave`** during an incident unless you've thought it through. Leaving a node from a 3-cluster leaves a 2-cluster, which has the split-brain ambiguity from POC 14. Better to fix the network and let the node rejoin.

---

## What's deliberately NOT in this repo

- **HTTPS API gateway / OAuth flow** — concerns of the upstream stack, not MQTT.
- **Time-series database tuning** — POCs sink to Postgres for readability; ClickHouse / TimescaleDB / InfluxDB are real-world targets but orthogonal to the MQTT patterns.
- **Sparkplug + Tahu integration** — POC 12 explains why we hand-rolled. For prod use Tahu.
- **Comprehensive integration tests** — Testcontainers is wired in the parent POM but per-POC tests are stubs. The POCs are demos; pin them to a real test pyramid before shipping.

---

## License

POC code: Apache-2.0.
Vendored `sparkplug_b.proto`: EPL-2.0 (Eclipse Foundation).
