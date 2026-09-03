# POC 01 — Million Connections

> **Goal:** Push one JVM to **100k+ concurrent MQTT sessions** against a single EMQX node. Document every wall you hit (file descriptors, ephemeral ports, Erlang scheduler binding) and how to break through it. This is the foundational POC — every connected-car, telematics, and smart-home platform lives or dies here.

## Why this is harder than it sounds

When you naïvely run `100,000` MQTT clients you hit four walls in this order:

| Wall | Symptom | Fix |
|---|---|---|
| **`ulimit -n`** (default 1024) | `Too many open files` after ~1000 connections | `ulimit -n 1048576` on **both** client and broker hosts; in systemd: `LimitNOFILE=1048576` |
| **Ephemeral port exhaustion** | Connections stall around 28k from a single client IP | `sysctl net.ipv4.ip_local_port_range="1024 65535"` (gives 64k); use multiple client IPs or `SO_REUSEPORT` |
| **TCP backlog overflow** | `connection reset by peer` in floods | Broker side: `net.core.somaxconn=65535`, EMQX listener `backlog=4096` |
| **Erlang scheduler saturation** | CPU at 100% on one core, 0% on others | `+sbt db` to bind schedulers; `+P 2000000` for max processes; `+Q 1048576` for max ports |

The Java side has its own walls. They show up later but are just as fatal — see "Why HiveMQ client and not Paho" below.

## What this module does

A Spring Boot app on **:8101** that:

1. Opens up to N MQTT 5 clients against EMQX (through HAProxy at `tcp://localhost:1880`) using the **HiveMQ async client** so all clients share **one Netty event-loop group**.
2. Paces connection establishment so we hit the broker at a controlled `connectsPerSec` — otherwise we'd hit POC 02's territory (connection storm) before we can measure anything.
3. Each connected client subscribes to `device/{clientId}/cmd` so we measure subscribe-tree cost, not just TCP.
4. Optionally trickles QoS 0 publishes from random fleet members to confirm sessions are alive and measure end-to-end latency at scale.

## Why HiveMQ client and not Paho

| | Paho | HiveMQ |
|---|---|---|
| Threading | Thread per client connection | Shared Netty event-loop pool |
| Max clients per JVM | ~5k (then OOM on threads) | 100k+ (limited by FDs + heap) |
| API | Sync + Async | Async + Reactive |
| MQTT 5 | Yes (separate JAR) | First-class |

Paho is fine for **one** client in a Spring service that does request/reply. It is wrong for fleet simulation. We ship both — Paho is the default elsewhere because most apps only need one client.

## Run

```bash
# 1. Start the cluster
docker compose up -d emqx1 haproxy prometheus grafana

# 2. Tune your local box (host where you'll run this JVM)
ulimit -n 1048576
sudo sysctl -w net.ipv4.ip_local_port_range="1024 65535"
sudo sysctl -w net.ipv4.tcp_tw_reuse=1

# 3. Start the app with a heap sized for the target
java -Xmx4g \
     -XX:MaxDirectMemorySize=2g \
     -Djdk.nio.maxCachedBufferSize=262144 \
     -jar 01-million-connections/target/poc-01-million-connections-1.0.0.jar

# 4. Drive a fleet up
curl -X POST 'localhost:8101/fleet/start?count=50000&rate=5000'
# Watch:
#   - localhost:18083 (EMQX dashboard, login: admin / public) - Live Stats
#   - localhost:3000 (Grafana) - "EMQX overview" dashboard
#   - localhost:8404 (HAProxy stats)

curl -X POST 'localhost:8101/fleet/traffic?rate=200'
```

## What "production-ready" means here

The app itself is not what you ship to production — **emqtt-bench** is. This POC exists so you (a) understand what the bench does internally and (b) can run a *Java-side* fleet that exercises the same code paths your real Java backend will exercise. Useful when your auth backend (POC 05) is the bottleneck, not the broker.

For the broker side, the production checklist is:

```bash
# /etc/security/limits.d/emqx.conf
emqx soft nofile 1048576
emqx hard nofile 1048576

# /etc/sysctl.d/99-emqx.conf
net.core.somaxconn = 65535
net.ipv4.tcp_max_syn_backlog = 65535
net.ipv4.ip_local_port_range = 1024 65535
net.ipv4.tcp_tw_reuse = 1
net.ipv4.tcp_fin_timeout = 15
net.core.netdev_max_backlog = 65535
net.ipv4.tcp_rmem = 4096 16384 16777216
net.ipv4.tcp_wmem = 4096 16384 16777216
fs.file-max = 2097152
fs.nr_open = 2097152

# emqx.conf
node.process_limit = 2097152
node.max_ports = 2097152
listeners.tcp.default.max_connections = 1500000
listeners.tcp.default.acceptors = 64
```

EMQX **Erlang VM args** (often missed):
```
-kernel inet_default_listen_options [{nodelay,true}]
-kernel inet_default_connect_options [{nodelay,true}]
+P 2097152
+Q 1048576
+sbt db
+swt very_low
+sub true
```

Cross-reference this with [the upstream tuning guide](https://docs.emqx.com/en/emqx/v5.8/performance/tune.html) — every value above is on that page; the goal of this POC is to *exercise the limits and feel them give way*.
