# POC 14 — EMQX Cluster + Split-brain

> **Goal:** Demonstrate routing across a 3-node EMQX cluster, then deliberately partition one node and observe split-brain from both broker side (Mgmt API) and client side (probe subscribers).

## EMQX 5 cluster model in one paragraph

EMQX 5 replaced classic Mnesia full-mesh replication with **Mria + RLOG**. Cluster state is split into two tiers:

- **Core nodes** form a quorum. They own the source of truth for routing tables, ACL caches, retained messages, and session metadata. Writes go through core.
- **Replicant nodes** stream a log from core (RLOG = Replicated Log). They serve client connections but don't participate in the consensus. Adding replicants scales out connection capacity without slowing core writes.

This POC's compose file uses 3 core nodes (no replicants) — it's the simplest setup and the one that exhibits split-brain most cleanly.

## Routing across the cluster

When `client A` connects to `emqx1` and subscribes to `t/foo`, `emqx1` writes to the cluster routing table: "the topic `t/foo` is interesting; route to node `emqx1`." Now when `client B` publishes to `t/foo` while connected to `emqx2`, `emqx2` consults the routing table, sees node `emqx1` cares, and forwards the message via the inter-node Erlang dist channel.

**Implication**: a 3-node cluster does NOT triple your throughput for a single hot topic — every publish to that topic incurs an extra cross-node hop. Sharing a heavily-published topic across nodes is fine; sharing a single fan-in topic is wasteful.

## Split-brain — what it looks like

A network partition that isolates one core from the other two leaves you with two halves that each think *they're* the cluster. Each half independently accepts connections, updates its routing table, and serves messages — but they don't see each other.

From the broker side: query each node's `/api/v5/cluster`. The `running_nodes` list will differ. That's the canonical split-brain signal.

From the client side (this POC): subscribers on the isolated node stop receiving messages published on the other side. The `/cluster/probe` endpoint detects this:

```json
{
  "corrId": "abc-123",
  "received": {
    "tcp://localhost:1883": 1,    // emqx1 publishes here and sees its own message
    "tcp://localhost:1884": 1,    // emqx2 still connected to emqx1 — got it
    "tcp://localhost:1885": 0     // emqx3 partitioned — missed it
  },
  "partitioned": ["tcp://localhost:1885"]
}
```

## How Mria handles partitions

EMQX 5's Mria layer uses **netsplit detection** based on the Erlang distribution heartbeat. On detection:

- **Auto-heal** (default ON): the minority side halts message routing and restarts when the partition heals. This is the "AP" choice — you prefer availability over consistency on the majority side, and accept that the minority side's clients are disconnected.
- **Auto-clean stale** (default ON): once the partition heals, stale routing entries on the rejoining node are dropped.

You CAN turn auto-heal off (`cluster.autoheal = false` in EMQX HOCON config) if you'd rather inspect the split manually. We leave it on by default.

## Run

```bash
# Start the 3-node cluster (already in docker-compose at the repo root)
docker compose up -d emqx1 emqx2 emqx3 haproxy postgres

# Wait for them to form a cluster
sleep 30
docker exec emqx1 emqx_ctl cluster status

# Start this POC
mvn -pl 14-cluster-split-brain spring-boot:run

# Sanity probe — all three should receive the message
curl -s localhost:8114/cluster/probe | jq

# All three nodes should agree on running_nodes
curl -s localhost:8114/cluster/membership | jq

# Cause split-brain: isolate emqx3 from the network
docker network disconnect emqx-production-patterns-poc_default emqx3

# Wait ~10s for netsplit detection, then probe again
sleep 15
curl -s localhost:8114/cluster/probe | jq
# → emqx3's subscriber will report received=0; "partitioned": ["tcp://localhost:1885"]

# emqx1 and emqx2 now see a 2-node cluster; emqx3 sees a 1-node cluster
curl -s localhost:8114/cluster/membership | jq

# Heal
docker network connect emqx-production-patterns-poc_default emqx3
sleep 15
curl -s localhost:8114/cluster/probe | jq   # all three again
```

## Things this POC exercises that are easy to break in production

1. **Bypassing the load balancer for diagnostics**. The probe connects directly to each broker (`:1883`, `:1884`, `:1885`). If you only ever talk via HAProxy, you cannot tell which node is partitioned because HAProxy will route you to the surviving side.
2. **Per-node Mgmt API**. The Mgmt API runs on each broker (default `:18083`). Hitting only one of them during an incident gives a partial picture.
3. **Auto-heal is non-deterministic**. On heal, you do NOT know in advance which side will be considered "majority" if the split is even (which is why you should have an odd cluster size — 3, 5, never 4).

## Why 3 nodes, not 2 or 4

- **2 nodes** can't disambiguate "the other guy is dead" from "the network is split". A 2-node cluster has no way to make a majority decision; one of them always has to be the chosen leader (RabbitMQ has this same problem).
- **4 nodes** can split 2-2. Neither side is a majority. Auto-heal can't pick a winner deterministically — you may need manual intervention.
- **3 / 5 / 7** are the sane sizes. 3 is the smallest cluster where you can survive any single-node failure or partition without ambiguity.

## What's NOT in this POC

- **Replicant nodes.** The model is built for them, but adding a replicant tier means a 5-container compose file and the split-brain story barely changes.
- **Cluster-time-skew detection.** Mria depends on rough clock agreement; severe skew can break routing-table sync. Run NTP.
- **Backplane-level network tuning.** The inter-node distribution channel (TCP port 4370) is what carries replication. In WAN-spanning clusters you want a separate "backplane" interface, often with link-level QoS.
- **Mria custom replication shards.** Out of scope; the default shards work fine for single-tenant clusters.

## Cluster-time gotchas (production)

- **Inter-node bandwidth.** Routing-table writes and inter-node forwarding share a TCP connection. A million subscribers across a million topics can saturate this. Monitor `emqx_dist_bytes_sent` per node.
- **DNS-based discovery.** EMQX 5 supports `cluster.discovery_strategy = dns` (k8s headless service) and `static` (compose). DNS is more dynamic but if your DNS lies during a partition you'll exacerbate the split. Static is safer for fixed-size clusters.
- **Shared subscriptions during split.** POC 04's `$share/group/topic` semantics: in a split, each side has its own consumer group from its own surviving subscribers, so a publish on each side hits one consumer on that side. After heal, the groups merge. This is usually fine but worth knowing if "exactly one consumer in the world" is your invariant.
