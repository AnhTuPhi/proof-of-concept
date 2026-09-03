# CONSISTENCY.md — Scaling this system (k8s pods / VMs) without breaking invariants

> Companion to [ISSUE.md](ISSUE.md) and [TECHNICAL.md](TECHNICAL.md).
>
> This document answers one question: **when you go from one instance to many — scaling
> EMQX nodes, or scaling the Spring apps as k8s pods / VMs — what invariants can silently
> break, and how do you keep them?**
>
> There are two independent axes of scaling here, and they fail differently:
>
> 1. **The broker cluster** (EMQX nodes) — stateful, quorum-based.
> 2. **The consumer/producer apps** (the 14 Spring apps) — should be stateless, but the
>    MQTT session model makes "stateless" a lie unless you design for it.

---

## 1. Mental model: where does state actually live?

| State | Lives in | Survives pod restart? | Survives node loss? |
|---|---|---|---|
| MQTT session (subs, inflight, queued QoS 1/2) | EMQX core nodes (Mria) | yes, if `sessionExpiry>0` and same clientId | yes (replicated across cores) |
| Routing table (topic → node) | EMQX core nodes (Mria/RLOG) | yes | yes |
| Retained messages | EMQX core nodes | yes | yes |
| Auth / ACL | Postgres | yes | yes (if PG is HA) |
| Device shadow (desired/reported) | Postgres JSONB | yes | yes |
| Telemetry / audit | Postgres + Kafka | yes | yes |
| **App in-memory counters/state** (e.g. POC 04 distribution map, POC 08 presence cache) | **the pod's heap** | **NO** | **NO** |

**The golden rule:** the broker and Postgres/Kafka are your durable tier. **The Spring apps
must hold no truth that can't be rebuilt from those.** Every scaling bug below is a violation
of this rule.

---

## 2. Scaling the EMQX cluster

### 2.1 Cluster size is a consensus decision, not a capacity dial
From [POC 14](14-cluster-split-brain/README.md): **always run an odd number of core nodes
(3, 5, 7)** — never 2 or 4.

- **2 nodes**: can't distinguish "peer dead" from "network split" → no majority.
- **4 nodes**: a 2-2 split has no majority → `autoheal` can't pick a winner deterministically.
- **3/5/7**: smallest sizes that survive one partition unambiguously.

This is a **HorizontalPodAutoscaler footgun**: you must **never** put EMQX core nodes under an
HPA that scales on CPU. An HPA can take you 3→4→2 and hand you a split-brain during the exact
incident you were trying to survive. Core nodes are a fixed-size StatefulSet.

### 2.2 Cores vs replicants: the right thing to autoscale
EMQX 5's Mria splits the cluster:

- **Core nodes** — own the routing table, sessions, retained, ACL cache. Writes go through
  core. **Fixed, odd count. Do not autoscale.**
- **Replicant nodes** — stream the RLOG, serve connections, don't vote. **This is the tier you
  scale for connection capacity.** A replicant HPA on connection count is safe because
  replicants don't affect quorum.

> This repo's compose is **core-only (3 nodes)** for the cleanest split-brain demo. A
> production k8s deployment adds a replicant `Deployment`/HPA on top. See TECHNICAL.md POC 14
> tech debt.

### 2.3 Discovery: `dns` on k8s, `static` on fixed VMs
- **k8s**: `cluster.discovery_strategy = dns` against a **headless Service**. Pods come and go;
  DNS reflects membership. Caveat from POC 14: if DNS *lies* during a partition it can worsen
  the split — set sane TTLs.
- **VMs / fixed compose**: `static` seed list (what this repo uses). Safer for fixed-size
  clusters because there's no DNS to lie.

### 2.4 The Erlang cookie is a cluster-join secret
All nodes must share `EMQX_NODE__COOKIE`. A mismatched cookie produces a **silent split** —
the node comes up "healthy" but never joins. In k8s put it in a `Secret` mounted identically
across the StatefulSet; never bake per-pod.

### 2.5 Inter-node bandwidth is a real ceiling
Routing writes + cross-node message forwarding share the Erlang distribution channel
(TCP 4370). A single hot fan-in topic pays an extra hop per publish (POC 14). At millions of
subscribers this saturates. Monitor `emqx_dist_bytes_sent` per node; in WAN-spanning clusters
give the backplane its own interface.

---

## 3. Scaling the apps — the part that silently corrupts

The 14 Spring apps are POC drivers, but the patterns generalize to *any* backend service that
speaks MQTT. When you run **N replicas** (k8s pods or VM instances), four things break.

### 3.1 ClientID collision — the #1 multi-pod bug
MQTT enforces **one connection per clientId cluster-wide**. If two pods connect with the same
clientId, the broker sends `0x8E Session taken over` and kicks the first — the two pods then
fight in an infinite reconnect loop (each kicking the other).

**This happens the instant you set `replicas: 2`** on any app that hard-codes a clientId.

**Fix — derive clientId from stable pod identity:**

```yaml
# k8s: use the StatefulSet ordinal or pod name
env:
  - name: POD_NAME
    valueFrom: { fieldRef: { fieldPath: metadata.name } }
```
```properties
# application.yml
mqtt.client-id: ${spring.application.name}-${POD_NAME:local}
```

- **StatefulSet** → stable ordinals (`app-0`, `app-1`) → deterministic, restart-stable
  clientIds. **Preferred for MQTT consumers.**
- **Deployment** → random pod names → use them, but know the clientId changes on every
  reschedule (fine for `cleanStart=true` stateless consumers; **wrong** for persistent
  sessions — you'll orphan a session per reschedule, straight into [POC 09](09-session-persistence/README.md)'s
  zombie-session trap).

### 3.2 Shared subscriptions — the ONLY safe way to fan out work across pods
From [POC 04](04-shared-subscriptions/README.md): a plain subscription delivers **every**
message to **every** pod. If 3 pods each `subscribe("telemetry/#")`, every telemetry message is
processed 3×.

**Fix:** every horizontally-scaled consumer subscribes to `$share/<group>/<topic>`. The broker
then delivers each message to exactly one pod in the group.

| You want… | Do this |
|---|---|
| N pods split the load, each msg once | all pods → `$share/ingest/telemetry/#` (same group) |
| Two independent pipelines (lake + alerts), each sees everything | group `lake` and group `alerts`, each their own `$share/<group>/...` |
| Per-device ordering preserved across pods | broker `shared_subscription_strategy = hash_clientid` |

**Rebalance is free** (POC 04): scaling pods up/down just updates the broker's routing table —
no Kafka-style stop-the-world. But **no replay** — if all pods in a group are down, messages
drop (per QoS). Put Kafka ([POC 06](06-rule-engine-kafka-bridge/README.md)) downstream if you
need replay.

### 3.3 In-memory app state does not survive a rescheduled pod
Several POCs keep state on the heap:

- POC 04's distribution counter, POC 08's presence cache, POC 09's received-count, POC 11's
  in-flight shadow view.

With 1 replica this is fine. With N replicas or a rescheduled pod, **each pod sees only its own
slice** and the number is wrong / lost on restart.

**Fix, in order of preference:**
1. **Don't hold it** — read from the durable tier (Postgres/Kafka) on demand.
2. **Externalize it** — Redis/Postgres for shared counters, if you truly need aggregate views.
3. **Make it per-pod-correct and aggregate at read time** — e.g. Prometheus scrapes each pod;
   Grafana sums. This is what the observability stack already does.

For shared-subscription consumers specifically: give each pod a **persistent session**
(`cleanStart=false`, `sessionExpiry>0`, stable clientId per §3.1) so its inflight slice
survives a restart instead of being redelivered/dropped.

### 3.4 Sticky routing must survive the LB *and* the k8s Service
[POC 09](09-session-persistence/README.md): a `cleanStart=false` client that reconnects to a
**different** node incurs a cross-node session fetch from Mria (slower under churn), and if the
session isn't found (expiry too short / node wiped) it silently starts fresh — you lose queued
messages.

- **In front of EMQX:** `balance source` (HAProxy in this repo) or NLB source-hash so a client
  IP sticks to a node.
- **In k8s:** a `LoadBalancer`/`Service` for the MQTT listener should set
  `sessionAffinity: ClientIP` (or use an NLB with source-hash). Round-robin kube-proxy will
  scatter reconnects across nodes and defeat stickiness.
- **Watch `sessionPresent`** on CONNACK as your smoke alarm (POC 09): a sudden drop in
  `sessionPresent=true` after a deploy means stickiness broke.

---

## 4. Consistency during a rolling deploy (the moment it all collides)

A rolling restart of either tier triggers several POCs at once. This is the scenario to
rehearse.

### 4.1 Rolling-restarting the EMQX cluster
1. Restarting a node drops its clients → they reconnect → **[POC 02](02-connection-storm/README.md) connection storm.**
   - Mitigation: clients ship **decorrelated jitter**; temporarily raise `max_conn_rate` on the
     cluster before the deploy so it can absorb the herd.
2. Take **one core node at a time**, wait for `emqx_ctl cluster status` to show it rejoined and
   the routing table synced **before** the next. Never drop two of three cores at once — that's
   a self-inflicted split-brain (POC 14).
3. `PodDisruptionBudget: maxUnavailable: 1` on the core StatefulSet enforces this in k8s.
4. **Do not** `emqx_ctl cluster leave` during the deploy — leaving a 3-cluster gives you a
   2-cluster with the split-brain ambiguity (README production note #9).

### 4.2 Rolling-restarting the apps
1. Persistent-session consumers: `sessionExpiry` must be **longer than the deploy window** so a
   pod's session survives the gap between `SIGTERM` and the replacement pod connecting.
2. Shared-subscription consumers: as each pod drops, its slice rebalances to survivors
   automatically (POC 04) — fine, as long as `terminationGracePeriodSeconds` lets in-flight
   messages ack first.
3. Stable clientIds (§3.1) mean the replacement pod **resumes the same session** rather than
   orphaning one and creating a new one.

### 4.3 The failure to avoid
Deploying apps and the broker **simultaneously** stacks a connection storm on top of a
cross-node session-fetch surge on top of possible split-brain. Sequence them: broker first
(one node at a time, fully healed), apps second.

---

## 5. Scaling checklist (copy into your runbook)

**EMQX cluster**
- [ ] Odd core count (3/5/7); cores are a fixed StatefulSet, **never** under an HPA.
- [ ] Scale connections via **replicant** nodes, not cores.
- [ ] `discovery_strategy = dns` (headless Service) on k8s; `static` on fixed VMs.
- [ ] Node cookie in a shared Secret, identical across pods.
- [ ] `PodDisruptionBudget maxUnavailable: 1` on cores.
- [ ] Monitor `emqx_dist_bytes_sent`, `emqx_message_dropped`, `sessionPresent` rate.

**Apps (per replica)**
- [ ] clientId derived from stable pod identity (StatefulSet ordinal / pod name).
- [ ] Horizontally-scaled consumers use `$share/<group>/<topic>` — never a plain sub.
- [ ] No truth held only in pod heap; rebuild from Postgres/Kafka or externalize.
- [ ] Persistent-session consumers: `cleanStart=false`, `sessionExpiry > deploy window`.
- [ ] MQTT Service uses `sessionAffinity: ClientIP` / source-hash NLB.
- [ ] `terminationGracePeriodSeconds` long enough to drain inflight before exit.

**Rolling deploy**
- [ ] Broker first (one core at a time, wait for full sync), apps second — never together.
- [ ] Pre-raise `max_conn_rate`; rely on client decorrelated jitter for the reconnect wave.
- [ ] Never `cluster leave` during an incident.

---

## 6. Quick reference — which POC governs which scaling concern

| Scaling concern | Governing POC |
|---|---|
| Connection capacity per node/JVM | [01](01-million-connections/README.md) |
| Reconnect storm during scale/deploy | [02](02-connection-storm/README.md) |
| Throughput vs delivery guarantee per replica | [03](03-qos-levels/README.md) |
| Fan-out work across pods (the core multi-pod pattern) | [04](04-shared-subscriptions/README.md) |
| Per-pod identity, tenant isolation | [05](05-auth-jwt-mtls/README.md) |
| Durable downstream / replay | [06](06-rule-engine-kafka-bridge/README.md) |
| Session-table growth from pod churn | [09](09-session-persistence/README.md) |
| Retained-table growth from many producers | [10](10-retained-messages/README.md) |
| Durable desired-state across restarts | [11](11-device-shadow/README.md) |
| Cluster sizing, split-brain, rolling restart | [14](14-cluster-split-brain/README.md) |
