# POC 02 — Connection Storm (Thundering Herd)

> **Goal:** Reproduce the most common EMQX production incident — a broker restart triggers all clients to reconnect simultaneously, the broker collapses under the second wave, the third wave fails altogether. Then show that **jittered exponential backoff** + **broker-side `max_conn_rate`** fix it.

## The actual incident

Time | Event
---|---
T+0 | Broker restarts (rolling deploy, OOM, anything)
T+0.1s | 100k clients all see their TCP connection close
T+0.5s | All 100k attempt to reconnect at once
T+1s | Broker accepts ~50k, drops the rest (`net.core.somaxconn` overflow)
T+2s | The 50k dropped clients retry immediately → second wave
T+5s | Broker CPU pegged at 100% on auth, can't service real traffic
T+30s | Devices think they're permanently offline; on-call paged

The root cause is *not* the broker — it's that **every client uses the same reconnect policy with no randomization**, so they synchronize.

## What this POC does

A Spring Boot app on **:8102** that maintains N persistent MQTT clients and lets you swap the reconnect strategy at runtime:

```bash
# Steady state
curl -X POST 'localhost:8102/storm/setup?count=10000'

# THE STORM (option A — restart broker)
docker restart emqx1

# THE STORM (option B — make our app force-disconnect, no broker restart needed)
curl -X POST 'localhost:8102/storm/disconnect-all'

# Try each strategy, observe in Grafana / EMQX dashboard:
curl -X POST 'localhost:8102/storm/strategy?name=IMMEDIATE'             # disaster
curl -X POST 'localhost:8102/storm/strategy?name=FIXED_1S'              # still bad
curl -X POST 'localhost:8102/storm/strategy?name=EXPONENTIAL_NO_JITTER' # the textbook answer is wrong
curl -X POST 'localhost:8102/storm/strategy?name=FULL_JITTER'           # acceptable
curl -X POST 'localhost:8102/storm/strategy?name=DECORRELATED_JITTER'   # best
```

## Why "exponential without jitter" still fails

Because every client doubles from the same base, every client picks the **same** delays (1s, 2s, 4s, 8s). The herd is delayed but never split. You get periodic peaks every doubling instead of one peak — better, but still pathologically synchronized.

**Decorrelated jitter** (AWS recipe):

```
sleep = random_between(base, prev_sleep * 3)   # capped
```

Each client carries its own random state forward, so within ~3 cycles the fleet is uniformly distributed across the window. See [`common/.../JitteredBackoff.java`](../common/src/main/java/com/claude/emqx/common/util/JitteredBackoff.java).

## Broker-side defence (always required even with good clients)

```hocon
# emqx.conf
listeners.tcp.default.max_conn_rate = 5000   # ramp up the cluster, not the wall
# overload protector kicks in at 80% mailbox usage
sysmon.mqtt {
  busy_dist_port = true
  busy_port = true
}
```

The client and broker fixes are complementary. Either alone leaves a vulnerability:
- Client fix without broker rate-limit → one bad-firmware fleet can still storm
- Broker rate-limit without client jitter → broker survives, but devices take minutes to recover

## What "production-ready" looks like

The strategy you actually ship on devices:

```java
JitteredBackoff backoff = new JitteredBackoff(
    Duration.ofMillis(500),   // base
    Duration.ofSeconds(60));  // cap — long enough that an angry fleet ≤ 60s of stress
```

Plus on the broker:
1. `max_conn_rate` per listener
2. `overload_protection` enabled with sensible thresholds
3. Pre-warmed cluster: if you're about to do a rolling restart, raise `max_conn_rate` temporarily so the cluster can absorb the herd

Note: this POC focuses on **connection** storms. Subscription storms (every client re-subscribing to a wildcard like `+/cmd`) are structurally similar but stress the topic tree instead of accept-queues. The fix is the same (jitter), but the symptom shows up in Mria sync metrics rather than `tcp_max_syn_backlog`.
