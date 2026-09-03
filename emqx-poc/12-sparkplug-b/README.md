# POC 12 — Sparkplug B on EMQX

> **Goal:** Implement the Sparkplug B v3 spec end-to-end (edge node + host application) over EMQX. Show the BIRTH/DEATH lifecycle, protobuf payloads, sequence-number gap detection, and why the LWT-as-NDEATH pattern is mandatory.

## Why Sparkplug exists

Plain MQTT gives you topics and QoS. It does NOT give you:

- **Stateful semantics** ("what does this device currently report?")
- **Liveness signal** (you only find out a device is gone via a keepalive timeout, ~45s late)
- **Self-describing payloads** (every team invents their own JSON schema)
- **A defined relationship** between edge gateways and the devices behind them

Sparkplug B fills these gaps with a *convention* on top of MQTT, plus a protobuf payload format. It's an Eclipse Foundation spec, adopted by Ignition, Cirrus Link, Inductive Automation, and the OPC UA → MQTT crowd.

## Topic structure

```
spBv1.0/{group_id}/{msg_type}/{edge_node_id}[/{device_id}]
```

Message types this POC handles:

| Type    | Meaning                                          | QoS | Retain |
|---------|--------------------------------------------------|-----|--------|
| NBIRTH  | Edge node online; declares all metrics           | 1   | false  |
| NDATA   | Edge node metric update                          | 0   | false  |
| NDEATH  | Edge node offline (LWT or explicit)              | 1   | false  |
| DBIRTH  | Device behind this edge node online              | 1   | false  |
| DDATA   | Device metric update                             | 0   | false  |
| DDEATH  | Device offline                                   | 1   | false  |

Conspicuously *not* retained. Sparkplug deliberately treats state as ephemeral and rebuilds it via NBIRTH on every reconnect. This is why retained-message broker rot (POC 10) doesn't affect Sparkplug fleets.

## The LWT-as-NDEATH pattern

The most important trick in Sparkplug. The edge node connects with an MQTT Last-Will-and-Testament set to its own NDEATH payload. If the node vanishes ungracefully (power yanked, NIC died, host crashed), the broker fires NDEATH the moment it detects the dead TCP connection.

```java
opts.setWillDestination("spBv1.0/" + group + "/NDEATH/" + node);
opts.setWillMessage(deathPayload);
```

Without this pattern, the only signal of a dead node is the keepalive timeout — minimum 1.5× the keepalive interval. With a 30s keepalive, you don't know a node is dead for **at least 45 seconds**. For a SCADA loop, that's a lifetime.

## bdSeq — pairing births with deaths

Each session, the edge node picks a `bdSeq` (a uint64) and includes it as a metric in BOTH the NBIRTH and the NDEATH. The host application uses this to ignore stale NDEATHs:

> "I got an NDEATH for node X with bdSeq=42, but the live session is on bdSeq=43. That NDEATH is from a previous session — ignore it."

This guards against the race where the broker fires the LWT after the node has already reconnected. Without bdSeq, a stale LWT would mark a healthy node dead.

## Sequence numbers

Every payload (NBIRTH = 0, then NDATA / DBIRTH / DDATA / etc.) carries `seq`, monotonic mod 256. The host checks every incoming `seq == (last + 1) & 0xFF`. A gap means QoS 0 messages were lost — Sparkplug's recovery is for the host to issue `NCMD` with `Node Control/Rebirth = true`, and the edge node responds with a fresh NBIRTH. (This POC logs the gap but does not actually publish the NCMD; a TODO in `SparkplugHostApplication`.)

## Protobuf payload

Vendored Eclipse `.proto` at `src/main/proto/sparkplug_b.proto`. Generated at build time by `protobuf-maven-plugin` into `org.eclipse.tahu.protobuf.SparkplugBProto`.

A `Payload` is a list of `Metric` plus a `seq`. Each `Metric` carries `name`, `timestamp`, `datatype` (integer from the spec's data-type table — see [SparkplugDataType](src/main/java/com/claude/emqx/sparkplug/SparkplugDataType.java)), and a `oneof value`. The datatype tag is REQUIRED — protobuf alone can't tell `int_value=5` was meant as Int8 vs Int16 vs UInt32, and the host can't know how to render it without the tag.

We trimmed the `Template` and `DataSet` types from the .proto for clarity. Production code should use the full schema (Eclipse Tahu ships a jar with the generated classes — `org.eclipse.tahu:tahu-core`).

## Run

```bash
# Spawn an edge node in group "Plant1"
curl -X POST 'localhost:8112/sparkplug/spawn?group=Plant1&node=Line-A'

# Birth a device behind it
curl -X POST 'localhost:8112/sparkplug/device-birth?group=Plant1&node=Line-A&device=Robot-1'

# Push some NDATA
curl -X POST 'localhost:8112/sparkplug/data?group=Plant1&node=Line-A&metric=Tank/Pressure&value=42.5'
curl -X POST 'localhost:8112/sparkplug/data?group=Plant1&node=Line-A&metric=Tank/Pressure&value=43.1'

# Host's view
curl 'localhost:8112/sparkplug/state' | jq

# Yank the cord. Watch the host log for NDEATH ~45s later.
curl -X POST 'localhost:8112/sparkplug/kill?group=Plant1&node=Line-A'
```

Watch what's on the wire:

```bash
mosquitto_sub -h localhost -p 1883 -u backend-svc -P backend-secret \
  -t 'spBv1.0/#' -v
```

You'll see binary protobuf — fine, the host decodes it in Java. To eyeball metrics, use `tahu-cli` or the EMQX dashboard's payload-format = sparkplug-b option.

## Sparkplug + EMQX gotchas

1. **Disable EMQX's retainer for `spBv1.0/#`** at the listener level. Sparkplug must not have retained messages — if a misconfigured client publishes retained, you'll get stale BIRTHs replayed and the host will believe a dead node is alive.
2. **Don't enable shared subscriptions on the host side.** A Sparkplug host expects to see every message exactly once and maintain a single coherent state. Spreading the stream across a consumer group breaks that. If you need HA, use active/standby instead.
3. **Connect with cleanStart=true**. The spec mandates it (sec 5.4). EMQX won't reject cleanStart=false but the host will see duplicate births when a queued NBIRTH from a previous session replays.
4. **Keepalive ≤ 30s**. Trade-off: shorter keepalive = faster NDEATH; longer = less PINGREQ traffic. 30s is the de-facto Sparkplug default.

## Why not just Tahu?

Eclipse Tahu (`org.eclipse.tahu:tahu-core`, `tahu-edge-sdk`) is the reference implementation. It's a hard production dependency, and it bundles its own Paho v3 client which doesn't compose with our MQTT 5 stack. For a POC where we want to *see* the protocol on the wire, hand-rolling the edge-node and host is more instructive. For prod, use Tahu — it has years of corner-case fixes we'd be re-discovering.

## What's not in this POC

- **DCMD / NCMD** publish-side (we receive but don't issue rebirth commands)
- **STATE** topic — the host's own LWT advertising whether the SCADA system is online
- **Template** and **DataSet** metric types (trimmed from the .proto)
- **Aliases** — Sparkplug lets you replace metric names with uint64 aliases on every NDATA after the NBIRTH defined them. Big bandwidth win for narrow links; orthogonal to the lifecycle this POC focuses on.
