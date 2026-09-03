# TECHNICAL.md — How each POC solves its hard problem

> Companion to [ISSUE.md](ISSUE.md) (what breaks) and [CONSISTENCY.md](CONSISTENCY.md)
> (behaviour when you scale the apps on k8s / VMs).
>
> Each section below follows the same skeleton so you can scan:
>
> - **Hard problem** — the non-obvious core difficulty.
> - **What we're protecting** — the asset/invariant that must survive.
> - **Solution shape** — the approach in 2–3 sentences.
> - **Key tech by responsibility** — who does what.
> - **How it solves each sub-problem** — the breakdown.
> - **Tech debt to acknowledge** — what we knowingly left rough.

---

## 0. Shared foundation

Before the individual POCs, the pieces every POC leans on.

### What we're protecting (globally)
- **The broker's Erlang VM heap and schedulers** — almost every failure mode ends in
  broker OOM or a pegged scheduler.
- **Message delivery invariants** — "at-least-once means at-least-once," "exactly one
  consumer in the group," "the device eventually converges to desired state."
- **Per-device identity and tenant isolation** — no cross-tenant leakage, no shared secrets.

### Key tech by responsibility

| Responsibility | Technology | Why |
|---|---|---|
| Broker | **EMQX 5.8** (3 core nodes) | Erlang/OTP scales to millions of connections; Mria+RLOG cluster; built-in rule engine |
| Client — scale POCs (01, 02) | **HiveMQ MQTT client 1.3** | Shared Netty event loop → 100k+ conns per JVM |
| Client — everything else | **Eclipse Paho v5 1.2.5** | Thread-per-client, trivial to reason about for single-client request/reply |
| App framework | **Spring Boot 3.4 / Java 21** | REST surface per POC, DI, virtual-thread-friendly |
| LB | **HAProxy 2.9** (`balance source`) | Sticky sessions so cleanStart=false clients land on the same node |
| Stateful store | **Postgres 16** | Auth/ACL, device shadow JSONB, telemetry sink, audit |
| Stream sink | **Kafka 3.9** | Durable replay downstream of the fire-and-forget broker |
| Observability | **Prometheus + Grafana** | `emqx_message_dropped` is the primary smoke alarm |
| Backoff math | **Resilience4j + custom `JitteredBackoff`** | Decorrelated jitter (AWS recipe) |
| mTLS CA | **BouncyCastle 1.78** (in-memory X.509) | Issue device certs without external PKI for the demo |
| Industrial payloads | **Protobuf 3.25** (Sparkplug B) | Eclipse-spec wire format |

### Two client libraries, one decision rule
`Paho` = one client per JVM, synchronous mental model, request/reply. `HiveMQ` = one Netty
event-loop group, 100k+ clients per process, async/reactive. **Use Paho unless you are
simulating a fleet** (POC 01, 02). This is documented once here and referenced everywhere.

---

## POC 01 — Million Connections

**Hard problem.** "A million connections" is not a RAM problem; it's *four* separate walls
hit in a fixed order, each with a different owner (OS, network stack, kernel, Erlang VM).
Miss any one and you plateau far below target with a misleading symptom.

**What we're protecting.** The ability to actually *reach* target connection count so every
other POC's scale claim is credible; and the broker's scheduler distribution across cores.

**Solution shape.** Ramp clients through HAProxy using the HiveMQ shared-event-loop client
at a *paced* `connectsPerSec` (so we measure steady-state, not a storm), each subscribing to
`device/{clientId}/cmd` to exercise the subscribe-tree cost — while the host and broker are
tuned per an explicit checklist.

**How it solves each sub-problem.**

| Wall | Owner | Fix applied |
|---|---|---|
| `ulimit -n` (1024) → "Too many open files" | OS | `ulimit -n 1048576` on client **and** broker; `LimitNOFILE` in systemd |
| Ephemeral port exhaustion (~28k/IP) | Network stack | widen `ip_local_port_range`, `tcp_tw_reuse`, multiple client IPs / `SO_REUSEPORT` |
| TCP backlog overflow → RST under flood | Kernel | `somaxconn=65535`, EMQX listener `backlog=4096` |
| Erlang scheduler saturation (1 core hot) | Erlang VM | `+sbt db`, `+P 2097152`, `+Q 1048576` |
| JVM thread explosion (~5k Paho conns) | Client lib | HiveMQ shared Netty pool instead of thread-per-client |

**Tech debt to acknowledge.** The Java fleet app is *not* what you ship — **emqtt-bench**
is the real load tool (it's in the compose file). This POC exists so your Java auth/backend
code paths get exercised at scale, not to replace the bench.

---

## POC 02 — Connection Storm (Thundering Herd)

**Hard problem.** The root cause of the single most common EMQX incident is *not* the broker
— it's that every client shares the **same** reconnect policy, so they synchronize. And the
textbook fix ("exponential backoff") **still fails**, because every client doubles from the
same base and picks the same delays.

**What we're protecting.** The *surviving* brokers during a restart — they must absorb the
reconnect wave without the second/third wave collapsing them.

**Solution shape.** A runtime-swappable reconnect strategy over N persistent clients, so you
can watch each strategy's herd shape in Grafana; the winner is **decorrelated jitter**
(`sleep = random(base, prev*3)`, capped), paired with **broker-side `max_conn_rate`**.

**How it solves each sub-problem.**
- *Synchronization* → jitter carries per-client random state forward; within ~3 cycles the
  fleet is uniform across the window.
- *"Exponential is enough" myth* → demonstrated to still peak at every doubling.
- *One bad-firmware fleet can still storm* → broker `max_conn_rate` + overload protection is
  the complementary defence; client-fix and broker-fix each alone leave a hole.

**Tech debt to acknowledge.** Focuses on *connection* storms. **Subscription** storms (mass
re-subscribe to a wildcard) are structurally identical but stress the topic tree / Mria sync
instead of accept queues — same fix (jitter), different metric to watch.

---

## POC 03 — QoS Levels

**Hard problem.** "Exactly once" *sounds* strictly safer, so teams default to QoS 2 and eat
a ~10× throughput cut plus per-inflight broker memory — without ever measuring it.

**What we're protecting.** Broker heap (per-inflight-message state) and end-to-end throughput.

**Solution shape.** A single-endpoint benchmark that runs the same payload at QoS 0/1/2 and
reports delivered / duplicates / avg-latency / throughput side by side, making the ratio
undeniable (`QoS0 ≫ QoS1 ≫ QoS2`, ~2–3× drop per level).

**How it solves each sub-problem.**
- *QoS 1 duplicates* → shown and explained (PUBLISH retransmit before PUBACK) → receivers
  must be idempotent.
- *QoS 2 cost* → 4-step handshake (PUBLISH→PUBREC→PUBREL→PUBCOMP) + stored packet IDs made
  visible as the throughput floor.
- *Guidance* → QoS 1 + idempotent processing is the default; QoS 2 only for genuinely
  non-idempotent, low-volume commands.

**Tech debt to acknowledge.** Numbers are laptop/Docker single-broker figures; only the
*ratio* is portable, not the absolute throughput.

---

## POC 04 — Shared Subscriptions

**Hard problem.** Scaling backend consumers behind a broker means one message must reach
**exactly one** of N consumers — but MQTT's default is fan-out-to-all. And the broker (not
the consumer) decides who gets each message, which changes ordering/replay vs Kafka.

**What we're protecting.** The "exactly one consumer per message per group" invariant, and
even load distribution across consumers.

**Solution shape.** `$share/group/topic` subscriptions with a live distribution counter;
multiple groups each get a full copy, consumers within a group split the load — no
consumer-side rebalance, the broker just updates its routing table.

**How it solves each sub-problem.**
- *Even load* → `random`/`round_robin` strategy; distribution endpoint proves the spread.
- *Scale up/down without coordination* → no stop-the-world rebalance (unlike Kafka).
- *Consumer death* → its slice goes to survivors; persistent sessions (POC 09) keep inflight.
- *Ordering per device* → `hash_clientid` strategy; per-topic → `hash_topic`.

**Tech debt to acknowledge.** **No replay** — if the whole group is down, messages drop
(subject to QoS). If you need replay, put Kafka (POC 06) downstream. Slow-consumer
backpressure (POC 10) still applies within a group.

---

## POC 05 — Auth: JWT + mTLS + HTTP + Postgres ACL

**Hard problem.** "Password auth" with a shared secret gives you no per-device identity and
leaks across tenants by default. Real auth needs per-device identity, a cheap common path,
and default-deny multi-tenancy.

**What we're protecting.** Per-device identity and **tenant isolation** — device-A must never
touch tenant-B's topics.

**Solution shape.** Four auth backends wired side-by-side (Postgres password, JWT HS256,
HTTP callback, mTLS) behind an **ordered authentication chain**, plus a default-deny ACL on
the `tenant/{tenant_id}/devices/{device_id}/...` convention.

**Key tech by responsibility.**

| Responsibility | Tech |
|---|---|
| Identity proof | mTLS (cert CN → username) — hardware-backed, no shared secret |
| Claims transport | JWT HS256 (`tenant_id`, optional inline `acl`) — HMAC verify is the cheapest path |
| External IDP hook | HTTP backend (Spring app answers allow/deny) |
| DB-managed ACL | Postgres `mqtt_user` / `mqtt_acl` + per-connection cache |
| Demo CA | BouncyCastle in-memory X.509 |

**How it solves each sub-problem.**
- *Cheap common path* → chain ordered JWT (in-memory HMAC) **before** Postgres (DB round-trip).
- *Cross-tenant leak* → `authorization.no_match = deny`; you only write allow rules.
- *ACL cost* → per-connection cache: ~1µs cached vs ~1ms uncached — enable it even at small scale.
- *Skip the DB entirely* → JWT inline `acl` claim → auth+authz in ~50µs.
- *mTLS at scale* → ECDSA P-256 (~1ms/handshake) over RSA-2048 (~5ms); cert rotation at 75% TTL; OCSP over CRL past ~10k revocations.

**Tech debt to acknowledge.** The CA private key is in-process for the demo — in production
it belongs in an HSM/KMS, never on the EMQX or app host. TLS should be 1.3-only.

---

## POC 06 — Rule Engine + Kafka & Postgres Bridges

**Hard problem.** The reflex is to write a Spring/Node microservice that subscribes MQTT and
writes Kafka/DB. That's a service to run, scale, monitor — and it adds latency the broker's
own rule engine wouldn't.

**What we're protecting.** Operational simplicity + broker CPU budget (a rule-engine sink at
100k msg/s ≈ 1 core; the equivalent Spring Kafka publisher ≈ 4 cores plus its ops cost).

**Solution shape.** At startup the app **provisions via the EMQX Mgmt API** (not HOCON files):
Kafka + Postgres connectors, two actions, and a SQL rule
(`SELECT * FROM "tenant/+/devices/+/telemetry" WHERE payload.metrics.temp_c > 0`) that fans
telemetry into both sinks; a verification consumer reads back from Kafka.

**How it solves each sub-problem.**
- *No middle service* → routing + transform + sink happen inside the broker with batching and
  backpressure built in.
- *Config drift / untestable HOCON* → Mgmt-API provisioning is hot-reloadable, validated, and
  identical against EMQX Cloud; the provisioning code lives in `EmqxRuleProvisioner`.
- *Durability/replay* → Kafka downstream gives the replay the broker can't.

**Tech debt to acknowledge.** Business logic in the rule SQL is still logic living outside
your app repo — keep it thin (routing/filtering), push real business rules to the Kafka
consumer where they're testable.

---

## POC 07 — MQTT 5 Features

**Hard problem.** On MQTT 3.1.1 every failure looks identical on the wire — no reason codes,
no headers, no way to alias long topics, no session TTL. You debug blind and pay per-packet
for repeated long topic strings.

**What we're protecting.** Debuggability and wire efficiency.

**Solution shape.** Demonstrate the four architecture-changing v5 features from Java —
reason codes, user properties, topic alias, request/response — and expose accumulated reason
codes over REST so you can see what the broker actually said.

**How it solves each sub-problem.**
- *Opaque failures* → reason codes on every ACK (`0x87 Not authorized`, `0x8D Keep-alive
  timeout`, `0x95 Packet too large`, …) distinguish causes.
- *Payload bloat for metadata* → user properties carry `traceId`/`tenant`/`schema_version`
  like Kafka headers.
- *Long topics* → topic alias: 200B → 6B per publish (huge for Sparkplug's 16-level topics).
- *DIY RPC* → native `responseTopic` + `correlationData`.

**Tech debt to acknowledge.** v3 clients still connect; they simply don't see these features.
The POC defaults everything to v5 but doesn't force-reject v3.

---

## POC 08 — LWT + Presence

**Hard problem.** Detecting that a device went offline via keepalive timeout is ~45s late
*and* false-positives on brief cellular reconnects.

**What we're protecting.** Accurate, timely online/offline state without polling or an extra
service.

**Solution shape.** Device connects with a **retained** Will (`presence/{id}` = offline) and a
**will-delay-interval** (MQTT 5); publishes retained "online" after connect; backend
subscribes `presence/+`. A hard-kill simulator proves the will fires; a graceful-disconnect
path proves it doesn't.

**How it solves each sub-problem.**
- *Slow detection* → broker fires the will on dead-TCP detection, not on a long timeout.
- *False offline on flap* → `will-delay` suppresses the will if the device reconnects within
  the window.
- *Late-joining backend* → retained presence gives current state immediately on subscribe.
- *Clean disconnect* → will does **not** fire; the app publishes "offline" itself.

**Tech debt to acknowledge.** Retained presence per device is a retained-message population —
subject to POC 10's cleanup discipline (set a TTL).

---

## POC 09 — Session Persistence

**Hard problem.** `cleanSession=false` + ever-changing/random clientIds leaks one session per
boot, kept forever → the broker session table grows to hundreds of millions of entries → OOM.

**What we're protecting.** Broker session-table memory and the "resume my queued QoS 1/2
messages" guarantee for devices that disconnect frequently.

**Solution shape.** Exercise all four `cleanStart × sessionExpiry` combinations and surface
the `sessionPresent` CONNACK flag, showing exactly what the broker holds (subscriptions,
inflight, queued QoS 1/2) and what MQTT 5's mandatory session-expiry fixes.

**How it solves each sub-problem.**
- *Zombie sessions* → MQTT 5 **requires** a session-expiry; recommended TTLs per device class
  (industrial 24h, consumer 2h, mobile 30m, backend 0).
- *"Did I get my session back?"* → log `sessionPresent`; a drop in `true` signals a
  session-store problem.
- *ClientID hygiene* → stable id (MAC/serial/cert fp) for devices; deterministic per-pod id
  for shared-sub consumers.
- *Cross-node resume* → HAProxy `balance source` keeps a client on its node.

**Tech debt to acknowledge.** QoS 0 is never queued even with a persistent session — if you
need offline delivery you must use QoS 1.

---

## POC 10 — Retained Messages + Cleanup Trap

**Hard problem.** Retained messages have **no default TTL** in MQTT 3.1.1. A fleet publishing
retained on every state change silently grows to tens of millions of live topics and swaps
the broker to death.

**What we're protecting.** The retainer table's footprint (the silent broker-killer that
isn't a connection storm).

**Solution shape.** Show the legitimate uses (current state, late-joiner, schema announce) and
the trap, then fix it with MQTT 5 per-message `messageExpiryInterval` **and** a broker-wide
`retainer.msg_expiry_interval` as defense in depth.

**How it solves each sub-problem.**
- *Unbounded growth* → per-message TTL on publish; broker-wide TTL as backstop.
- *Delete a retained value* → publish a zero-byte retained message.
- *Where retained makes no sense* → `retain_available=false` on high-frequency telemetry
  listeners.
- *Wildcard `#` debug subscribe* (a related killer) → flagged; it floods and backpressures.

**Tech debt to acknowledge.** The POC demonstrates the mechanism; picking the *right* TTL is
domain-specific and left to the operator.

---

## POC 11 — Device Shadow (AWS IoT-style)

**Hard problem.** The broker is fire-and-forget: "turn the heater off" sent while the device
is offline dies — even QoS 1, if the session expired. Desired-state for intermittent devices
needs durable state **off** the broker.

**What we're protecting.** Convergence of the device to backend intent across an intermittent
link, with durability across broker restarts.

**Solution shape.** Reported/desired/delta state in Postgres JSONB, mirroring AWS IoT topics
(`$devices/{id}/shadow/...`); delta = desired − reported is published **retained** so a device
joining a week later still gets the latest intent immediately.

**How it solves each sub-problem.**
- *Offline command loss* → source of truth is the DB, not the broker; retained delta survives.
- *Merge semantics* → JSONB `||` shallow-merge instead of retained's wholesale replace.
- *Auditability / invariants* → DB gives history + transactional guards retained can't.
- *SDK compatibility* → AWS-style topic convention so AWS-written device code works unchanged.

**Tech debt to acknowledge.** Demo uses `$devices/{deviceId}`; production should scope to
`$devices/{tenant}/{deviceId}`. Delta computation is shallow-merge — deep/nested desired
state needs a richer diff.

---

## POC 12 — Sparkplug B

**Hard problem.** Plain MQTT gives topics + QoS but no stateful semantics, no liveness signal,
no self-describing payloads, and no defined edge-node↔device relationship. Every SCADA team
reinvents these, incompatibly.

**What we're protecting.** Interoperability with the industrial ecosystem (Ignition, Cirrus
Link, OPC-UA→MQTT) and a reliable liveness signal.

**Solution shape.** Implement the Eclipse Sparkplug B v3 lifecycle end-to-end — NBIRTH/NDATA/
NDEATH, DBIRTH/DDATA/DDEATH — over protobuf payloads with `bdSeq` pairing and sequence-gap
detection, using **LWT-as-NDEATH** for hard-failure liveness.

**How it solves each sub-problem.**
- *No liveness* → LWT set to the node's NDEATH fires on dead-TCP detection.
- *State rebuild* → messages are deliberately **not retained**; state rebuilds via NBIRTH on
  reconnect (so POC 10's retained-rot never affects Sparkplug fleets).
- *Self-describing payloads* → protobuf metric definitions in BIRTH.
- *Gap detection* → monotonic `seq` per message; host detects drops and requests rebirth.
- *Long topics* → pairs naturally with POC 07 topic alias.

**Tech debt to acknowledge.** Hand-rolled to teach the spec — for production use **Eclipse
Tahu**. The vendored `sparkplug_b.proto` is EPL-2.0 (not Apache-2.0 like the rest).

---

## POC 13 — OTA Updates

**Hard problem.** Pushing a 1MB image to 1M devices at once = 1TB of broker bytes and
backpressure death — with no resume and no integrity check.

**What we're protecting.** Broker throughput during a rollout, and firmware integrity /
atomicity on each device.

**Solution shape.** **Chunked pull**, not push: a retained `offer` (30-day TTL) advertises the
current firmware; each device requests chunk windows `from..to`, gets one chunk per topic
(QoS 1, not retained), SHA-256 verifies, and resumes from a bitmap after a crash.

**How it solves each sub-problem.**
- *Broker meltdown* → device sets its own pace (backpressure); server never queues 5MB/device.
- *Resumability* → device tracks a chunk bitmap; asks only for what it's missing.
- *Natural stagger* → 1M devices waking at random offsets hash into a rollout curve.
- *Cheap loss recovery* → per-chunk QoS 1 retry costs ~4KB, not the whole image.
- *Integrity/atomicity* → SHA-256 verify before atomic apply.
- *Fleet-wide "what should I run?"* → retained offer (POC 10 pattern, with TTL).

**Tech debt to acknowledge.** For devices with spare bandwidth + reliable HTTPS, the orthodox
answer is still "trigger over MQTT, fetch over HTTPS/S3." MQTT-OTA is for cellular / firewalled
/ constrained MCUs where MQTT-over-TLS is the only path out.

---

## POC 14 — Cluster + Split-brain

**Hard problem.** "The cluster is up but messages aren't routing." A partition leaves two
halves each believing *they're* the cluster; even-sized clusters heal non-deterministically.

**What we're protecting.** Routing correctness across nodes and a **deterministic** heal after
a partition.

**Solution shape.** A per-node probe (connect directly to `:1883/:1884/:1885`, bypassing the
LB) plus the Mgmt-API membership view; a `docker network disconnect` demo induces the split
and the probe shows which node stopped receiving.

**Key tech by responsibility.**

| Responsibility | Tech |
|---|---|
| Cluster consensus | Mria + RLOG (core nodes own routing table; replicants stream the log) |
| Partition detection | Erlang distribution heartbeat / netsplit detection |
| Heal policy | `cluster.autoheal` (minority halts + restarts on heal); `auto-clean stale` |
| Diagnostics | Direct per-node MQTT probe + per-node Mgmt API |

**How it solves each sub-problem.**
- *Invisible partition* → probe bypasses HAProxy (which would route you to the surviving side
  and hide it).
- *Partial picture* → hit **each** node's Mgmt API, not just one.
- *Non-deterministic heal* → odd cluster size (3/5/7); 2 can't form majority, 4 splits 2-2.
- *Cross-node cost awareness* → a single hot fan-in topic pays an extra inter-node hop.

**Tech debt to acknowledge.** No replicant tier (core-only for the cleanest split story),
no time-skew detection (run NTP), no backplane network tuning, default Mria shards.

---

## Cross-cutting tech-debt summary

| Debt | Where | Mitigation for production |
|---|---|---|
| Per-POC tests are stubs | all | Testcontainers is wired; build a real test pyramid before shipping |
| CA key in-process | 05 | Move to HSM/KMS |
| Sink is Postgres for readability | 06, 11 | Real target is ClickHouse/Timescale/Influx |
| Hand-rolled Sparkplug | 12 | Use Eclipse Tahu |
| Rule SQL holds some logic | 06 | Keep rule thin; push logic to the Kafka consumer |
| Absolute benchmark numbers | 03 | Only the ratio is portable |
| Core-only cluster | 14 | Add a replicant tier for connection-scale-out |

For how these apps behave when you run **many replicas** of them on Kubernetes or across VMs
— clientId collisions, shared-subscription rebalancing, sticky-session routing, and
split-brain during a rolling deploy — see [CONSISTENCY.md](CONSISTENCY.md).
