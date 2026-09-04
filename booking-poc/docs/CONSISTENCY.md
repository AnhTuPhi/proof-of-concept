# booking-poc — Multi-replica consistency

Companion to [`TECHNICAL.md`](./TECHNICAL.md). Covers what stays correct when the app runs as >1 process — multiple VMs behind a load balancer, or a Kubernetes Deployment with N pods — and the two swaps you flip on for cluster-safe operation.

> TL;DR — the DB-backed pieces are already cluster-safe. The two things that aren't are **`SoftHoldStore` (Caffeine)** and **`HardHoldSweeper` (`@Scheduled`)**. Both swaps are already present in this codebase as alternative implementations guarded by config properties; flip the properties and provide the infra.

---

## 1. What "consistent" means here

| Invariant | Where enforced |
|---|---|
| At most one customer can simultaneously hold seat *X* | `SoftHoldStore.tryAcquire` (compare-and-set) + `SELECT FOR UPDATE` on the seat row |
| At most one paid sale per seat | `SeatStatus` transition under pessimistic lock |
| No two bookings overlap on an owner's calendar (modulo buffer) | `BookingRepository.findOverlappingForUpdate` (pessimistic lock) |
| Flight cannot exceed sell ceiling | `FlightRepository.findByCodeForUpdate` + count |
| Bumped passengers each carry the right compensation amount | Single boarding transaction; per-row ledger |

Every invariant above is enforced by the DB once a request lands at a service method. Adding more application replicas does not weaken any of them — the DB row is the serialization point.

---

## 2. What's already cluster-safe at any scale

| Mechanism | Why it scales unchanged |
|---|---|
| `@Lock(PESSIMISTIC_WRITE)` on `Seat`, `Flight`, overlap query | DB-level lock; all replicas contend at the DB. |
| `@Version` on `Seat`, `Flight` | Optimistic conflict; loser retries. |
| `HardHold` row + `paymentDeadline` column | Durable; survives pod restart, JVM crash, deploy. |
| All persisted entities & their transactions | DB is the single source of truth. |
| Spring `@Transactional` boundaries | Isolation level inherited from the DB connection. |

You can add replicas without touching anything above. The throughput ceiling becomes the DB connection pool: `pool_size_per_pod × replicas ≤ db_max_connections`.

---

## 3. What breaks at >1 replica (and the fix)

### 3.1 `SoftHoldStore` — per-JVM Caffeine

**Problem.** Two pods receive `POST /seat/12A/soft-hold` at the same millisecond. Each pod's Caffeine returns "winner". Both shoppers think they hold the seat. The race is only caught later when one of them promotes to a hard hold (`SELECT FOR UPDATE` saves us *correctness*), but the UX is broken — the loser has been told they own the seat for 5 minutes.

**Fix — already in the codebase.** [`RedisSoftHoldStore`](../src/main/java/com/example/bookingpoc/seat/service/RedisSoftHoldStore.java) implements the same `SoftHoldStore` interface using Redis `SET NX PX` for acquire and a Lua compare-and-delete for release. Activate with:

```yaml
spring:
  autoconfigure:
    exclude: []        # remove the RedisAutoConfiguration exclude
  data:
    redis:
      host: <your-redis-host>
      port: 6379
      password: <secret>
booking:
  seat:
    hold-store: redis
```

The Lua release script (in [`RedisConfig`](../src/main/java/com/example/bookingpoc/config/RedisConfig.java)):

```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
else
  return 0
end
```

**Why Lua matters.** Between `GET` and `DEL` the TTL can expire, another customer can acquire the slot, and a naive client would `DEL` *their* hold. Compare-and-delete prevents that classic distributed-locking footgun. (The daccount Redis lock pattern uses the same shape.)

**Production caveat in the skeleton.** The current implementation stores `customerId|token` as a single Redis string and the Lua compares the whole value. If a customerId ever contains `|`, the parse breaks. Upgrade to a Redis hash with `token` in its own field, and have the Lua compare only that field. This is one line of code change in `RedisSoftHoldStore` plus a swap to `HSETNX`-style acquire.

### 3.2 `HardHoldSweeper` — `@Scheduled` runs in every pod

**Problem.** With N replicas, the 30-second sweep fires N times every 30 seconds. Each pod's sweeper queries the same `PENDING` holds, takes the same row locks, and updates them. Correctness survives (the second sweeper sees status already `EXPIRED` and no-ops) but you pay N× the DB load and the locks flap.

**Fix — already in the codebase.** [`HardHoldSweeper.sweep`](../src/main/java/com/example/bookingpoc/seat/service/HardHoldSweeper.java) is annotated with:

```java
@SchedulerLock(name = "hardHoldSweep", lockAtMostFor = "PT2M", lockAtLeastFor = "PT25S")
```

The annotation is a no-op until [`SchedulerLockConfig`](../src/main/java/com/example/bookingpoc/config/SchedulerLockConfig.java) activates with `booking.scheduler.distributed=true`. That config wires a `JdbcTemplateLockProvider` on the existing `DataSource`. ShedLock uses the `shedlock` table to elect a single sweeper per interval.

- `lockAtMostFor = PT2M` — if the holding pod dies, no other pod takes over for 2 minutes. Safety net.
- `lockAtLeastFor = PT25S` — even if the sweep finishes in 100 ms, the lock is held for 25 s so the next interval's pod doesn't re-fire immediately.

The `shedlock` table DDL ships in `data.sql` with `IF NOT EXISTS` — harmless when the feature is off, present when you flip it on.

```yaml
booking:
  scheduler:
    distributed: true
```

---

## 4. Deployment topologies

### 4.1 Single VM

The POC code runs as-is. State lives in the JVM (Caffeine) + the DB (Hibernate). Failure modes:
- JVM crash → soft holds vanish (acceptable — customer was browsing); hard holds + sweeper recover at restart.
- Deploy = restart = downtime window. Use blue/green if zero-downtime is required.

No code or config change.

### 4.2 Multiple VMs behind a load balancer

Need both swaps from Section 3:

- `booking.seat.hold-store: redis` + provide Redis.
- `booking.scheduler.distributed: true` + provide the `shedlock` table.

Other knobs:
- Sticky sessions are **not** required; state is in Redis + DB.
- Shared configuration: Consul, AWS Parameter Store, Azure App Config — same `application.yml` per VM.
- Health checks: load balancer should poll `/actuator/health` (add `spring-boot-starter-actuator` dependency and `management.endpoint.health.show-details=when_authorized`).
- DB pool sizing: `hikari.maximum-pool-size × n_vms ≤ db_max_connections` minus headroom for ops tools.

### 4.3 Kubernetes

Same as multi-VM, plus k8s primitives. Example `Deployment` + `Service`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: booking-poc
  labels: { app: booking-poc }
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate: { maxSurge: 1, maxUnavailable: 0 }
  selector:
    matchLabels: { app: booking-poc }
  template:
    metadata:
      labels: { app: booking-poc }
    spec:
      terminationGracePeriodSeconds: 35
      containers:
        - name: app
          image: registry.example.com/booking-poc:0.1.0
          ports: [{ containerPort: 8080 }]
          env:
            - { name: SPRING_PROFILES_ACTIVE, value: prod }
            - { name: BOOKING_SEAT_HOLD_STORE, value: redis }
            - { name: BOOKING_SCHEDULER_DISTRIBUTED, value: "true" }
            - { name: SPRING_DATA_REDIS_HOST, value: redis-master.cache.svc }
            - { name: SPRING_AUTOCONFIGURE_EXCLUDE, value: "" }   # cancel the dev exclude
          envFrom:
            - secretRef: { name: booking-db }     # DB creds
            - secretRef: { name: booking-redis }  # REDIS creds
          resources:
            requests: { cpu: 250m, memory: 512Mi }
            limits:   { cpu: 1000m, memory: 1Gi }
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            periodSeconds: 5
            failureThreshold: 3
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            periodSeconds: 30
            failureThreshold: 5
          lifecycle:
            preStop:
              exec: { command: ["/bin/sh", "-c", "sleep 5"] }   # let endpoints drain
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                topologyKey: kubernetes.io/hostname
                labelSelector:
                  matchLabels: { app: booking-poc }
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata: { name: booking-poc }
spec:
  minAvailable: 2
  selector:
    matchLabels: { app: booking-poc }
---
apiVersion: v1
kind: Service
metadata: { name: booking-poc }
spec:
  selector: { app: booking-poc }
  ports:
    - port: 80
      targetPort: 8080
```

What each piece does:

| Setting | Purpose |
|---|---|
| `replicas: 3` | Three pods. Soft hold and sweep correctness handled by the Section 3 swaps. |
| `RollingUpdate maxUnavailable: 0` | Never drop below replica count during deploy. |
| `terminationGracePeriodSeconds: 35` | 5 s `preStop` sleep + 20 s graceful Spring shutdown + headroom. |
| `preStop: sleep 5` | Lets the Service's iptables / endpoint update propagate before the JVM stops accepting requests. |
| `readinessProbe` | Spring's `health/readiness` checks DB + Redis. Failing pod is removed from the Service. |
| `livenessProbe` | Strictly "process alive". Don't bind to DB health here or a DB blip restarts all pods. |
| `PodDisruptionBudget minAvailable: 2` | Node drains / cluster upgrades cannot kill more than 1 of 3 at a time. |
| `podAntiAffinity` | Spread across nodes / AZs for blast-radius reduction. |

To make Spring's `/health/readiness` include Redis: depend on `spring-boot-starter-actuator` and set `management.endpoint.health.probes.enabled=true`.

For graceful shutdown (so in-flight requests finish):

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 20s
```

---

## 5. Failure modes & what each looks like

| Event | Pod-local state lost | DB / Redis state | Customer impact |
|---|---|---|---|
| Single pod SIGTERM (rolling deploy) | Caffeine cache contents (only matters in `hold-store=memory` mode) | Untouched | Zero in redis-mode; ≤5 min wait for affected customers in memory-mode |
| Single pod OOM kill | Same as above | Untouched | Same as above |
| JVM full GC pause | Nothing lost | Untouched | Latency spike on requests routed to that pod |
| Redis primary failover | All in-flight Redis ops fail briefly | Holds in Redis preserved if AOF/RDB persistence on | Brief 5xx on `soft-hold`; client retry succeeds |
| DB primary failover | DB writes blocked during failover | Persisted state intact | Brief 5xx on writes; reads from replicas if read-replica routing exists |
| Network partition between pod and Redis | Affected pod's `soft-hold` calls fail | Other pods unaffected | Readiness probe should fail the partitioned pod within 15-30 s |
| Network partition between pod and DB | Same shape | Other pods unaffected | Pod fails readiness, removed from Service |
| ShedLock holder pod dies | Lock survives in `shedlock` table until `lockAtMostFor` expires (2 min) | Sweeper paused for ≤2 min | Hard holds expire ≤2 min later than ideal — acceptable |

The dangerous failure to avoid: a sweeper pod is *frozen* (long GC, network blip) but not dead. ShedLock can't tell it's frozen, so the lock stays held until `lockAtMostFor` expires. Tune `lockAtMostFor` to be larger than your worst expected GC pause + a margin.

---

## 6. Operational checklist before going multi-replica

Before flipping the switches:

- [ ] **Redis available** — HA recommended (Redis Sentinel or managed Elasticache/MemoryStore). Persistence enabled if you want hold survival across Redis restart.
- [ ] **`shedlock` table exists** — `CREATE TABLE IF NOT EXISTS` already in `data.sql`; verify it exists in your real DB.
- [ ] **DB connection pool sized** — `hikari.maximum-pool-size × replicas ≤ db_max_connections − ops_headroom`.
- [ ] **Graceful shutdown configured** — `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase` ≥ longest expected request.
- [ ] **Readiness + liveness split** — readiness checks dependencies (DB + Redis); liveness only checks process.
- [ ] **Metrics scraped** — Micrometer + Prometheus or your equivalent. Watch for: hold-acquire-loss rate per replica (should be even), sweeper-run rate (exactly one per interval when ShedLock is on), DB pool saturation.
- [ ] **Log aggregation** — single source for cross-pod tracing (Loki, Cloud Logging, ELK). Correlation IDs via Spring's `request-id` MDC.
- [ ] **Time skew across hosts** — chrony/ntpd healthy. ShedLock's `usingDbTime()` already insulates against host-clock skew by using the DB's clock, but `expireAfterWrite` in Caffeine and `Instant.now()` math depend on host wall-clock.

---

## 7. How to enable in this POC

Without changing any code:

### 7.1 Redis-backed soft hold

```bash
# 1. Start Redis (docker)
docker run -d --name redis -p 6379:6379 redis:7-alpine

# 2. Run app with overrides
mvn spring-boot:run \
  -Dspring-boot.run.arguments="\
    --booking.seat.hold-store=redis \
    --spring.autoconfigure.exclude= \
    --spring.data.redis.host=localhost \
    --spring.data.redis.port=6379"
```

### 7.2 ShedLock-coordinated sweeper

```bash
# Single command — the shedlock table is auto-created from data.sql
mvn spring-boot:run \
  -Dspring-boot.run.arguments="--booking.scheduler.distributed=true"
```

To prove it works locally: bump `replicas` by running on two ports (`--server.port=8081`) and watch the logs — only one will say "Released N expired hard holds" per sweep cycle.

### 7.3 Both at once (production-shaped local run)

```bash
mvn spring-boot:run \
  -Dspring-boot.run.arguments="\
    --booking.seat.hold-store=redis \
    --booking.scheduler.distributed=true \
    --spring.autoconfigure.exclude= \
    --spring.data.redis.host=localhost"
```

---

## 8. What's still single-process in this POC

These are not blockers for going multi-replica but are worth knowing:

| Behaviour | Where it lives | Cluster impact |
|---|---|---|
| `BookingPocApplication` `@EnableScheduling` | Spring bean | Every pod has scheduled tasks; only `HardHoldSweeper` is ShedLock-aware. If you add more, annotate them too. |
| `GlobalExceptionHandler` | Spring bean | Stateless; safe per pod. |
| Bumping decision history | Not persisted | Each `POST /board` is a single transaction; survives because of the row updates, but if you want to audit *who decided*, add a `bumping_decisions` table. |
| Slot expansion cache | Not implemented | `AvailabilityService.openSlots` recomputes per request. For a hot owner this should be cached — Redis again, with invalidation on `book()`. |

---

## 9. References

- [`TECHNICAL.md`](./TECHNICAL.md) — the per-pattern deep dive this doc references.
- [`flows.html`](../src/main/resources/static/flows.html) — visual state machines.
- [`SoftHoldStore`](../src/main/java/com/example/bookingpoc/seat/service/SoftHoldStore.java) — interface, both implementations.
- [`RedisSoftHoldStore`](../src/main/java/com/example/bookingpoc/seat/service/RedisSoftHoldStore.java) — multi-replica impl.
- [`SchedulerLockConfig`](../src/main/java/com/example/bookingpoc/config/SchedulerLockConfig.java) — ShedLock wiring.
- ShedLock docs: <https://github.com/lukas-krecan/ShedLock>
- Spring Data Redis: <https://docs.spring.io/spring-data/redis/reference/>
