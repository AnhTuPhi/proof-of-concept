# POC 07 — MQTT 5 Features

> **Goal:** Show the four MQTT 5 features that materially change architecture decisions, and how to use them from Java.

## What changes from MQTT 3.1.1

| Feature | What MQTT 3 had | What MQTT 5 has | Why it matters |
|---|---|---|---|
| **Reason codes on every packet** | CONNACK had a return code; DISCONNECT had nothing | Every ACK packet carries a reason code (1 byte) and optional reason string | Devices can tell `QuotaExceeded` from `KeepAliveTimeout` from `ServerShuttingDown`. Crucial for client-side handling logic. |
| **User properties** | Nothing | List of `(name, value)` pairs on CONNECT, PUBLISH, ACK packets | Headers, like Kafka. Carry `traceId`, `tenant`, `schema_version` without bloating payload. |
| **Topic alias** | Full topic name on every packet | First publish sends full name; map it to a 2-byte alias; subsequent publishes use only the alias | Massive win for long topics (Sparkplug B is 16+ levels). 200B → 6B per publish. |
| **Request / Response** | Roll your own correlation scheme | `responseTopic` + `correlationData` properties on PUBLISH | Native RPC-over-MQTT. Replies come back on a topic chosen by the requester. |
| **Session expiry interval** | `cleanSession=false` = forever | Explicit TTL in seconds | No more zombie sessions accumulating forever in the broker (POC 09). |
| **Server-side disconnect** | Only client could disconnect cleanly | Server can DISCONNECT with reason code | Broker can say "you're being rate-limited" instead of silently dropping. |
| **Shared subscriptions in spec** | EMQX-specific extension | Standardized | Multi-broker portability. |

## Run

```bash
# Make a request via MQTT 5 request/response
curl -X POST 'localhost:8107/mqtt5/request?body=ping&traceId=demo-1'
# {"response":"pong: ping","traceId":"demo-1"}

# See accumulated reason codes (from forced disconnects, etc.)
curl localhost:8107/mqtt5/reasons
```

## Reason codes that actually show up in production

| Code | Meaning | What it usually indicates |
|---|---|---|
| 0x00 Success | All good | — |
| 0x81 Malformed packet | Garbled bytes | Bad client lib, wrong port (e.g. MQTT to a TLS port) |
| 0x82 Protocol error | Broke the spec | Almost always a client bug |
| 0x87 Not authorized | Auth failed | Wrong creds, expired JWT |
| 0x8B Server shutting down | Broker restart | Devices reconnect; combined with sticky LB this is benign |
| 0x8D Keep alive timeout | Missed PINGREQs | Cellular flap, NAT timeout, app crash |
| 0x8E Session taken over | Same clientId connected from elsewhere | Often a rebooted device using a stale session |
| 0x93 Receive maximum exceeded | Client opened too many inflight | Application bug; lower batch size |
| 0x95 Packet too large | Exceeds `maxPacketSize` | Payload too big — consider chunking |
| 0x97 Quota exceeded | Rate limit hit | POC 02 backoff territory |
| 0x99 Payload format invalid | UTF-8 expected, got binary | Often a `contentType` mismatch |

Wire each into a per-code metric (`mqtt.disconnect.code{code=0x97}`). The mix tells you the health of the fleet.

## User properties — pattern of the year

Devices add:
```
User-Property: traceId=abc-123
User-Property: tenant=tenant-a
User-Property: schema=telemetry-v2
```

Rule engine (POC 06) can read user props in SQL with `header('traceId')`. Pass them through to Kafka headers in the bridge action. Now your tracing context flows end-to-end without ever touching the payload.

Don't abuse user properties for primary data (payload is for data). Use them for *metadata*: identity, schema, routing hints.

## Topic alias — when to bother

Worth it when:
- Topic > ~50 bytes
- Publish rate > 100 msgs/sec per client
- You're paying for cellular bytes (every byte matters at scale)

Skip when:
- Topics are short (< 20 chars)
- Connection short-lived (alias map costs you the first publish anyway)

Server config: `mqtt.max_topic_alias = 65535`. Client config: set `topicAlias=N` in publish properties; broker takes care of the rest.

## Request / Response — the cleanest RPC over MQTT

The pattern:

```
Device publishes to    rpc/{deviceId}/req     payload=request
                       Properties:
                         responseTopic = rpc/{deviceId}/resp
                         correlationData = <random bytes>
Backend subscribes to  rpc/+/req
Backend publishes to   rpc/{deviceId}/resp    payload=response
                       Properties:
                         correlationData = <echo back>
Device subscribes to   rpc/{deviceId}/resp
```

The correlation data is opaque bytes — typically a UUID. The requester correlates the response without needing to put anything in the payload. The Java side in this POC tracks pending requests in a `ConcurrentHashMap<correlationId, CompletableFuture>`.

Latency: ~1ms on a healthy broker for a 64-byte round-trip. Better than HTTP if your devices are already on MQTT.
