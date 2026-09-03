# CONSISTENCY.md — What happens when we scale the pods (or VMs)

The base POC runs **one JVM**. In production you'll want more — either horizontally-autoscaled
K8s pods or a fleet of VMs behind a load balancer. That change breaks assumptions the
single-node design silently relied on. This document is the checklist for keeping the
system correct when there is more than one of it.

The short version: **the Spring Batch metadata schema is what makes the fleet coherent.
Everything else follows from that.**

---

## 1. The three consistency risks scaling introduces

### Risk A — Duplicate work
Two pods pick up the same source file (or the same id range) at the same time. Both write
it. Without safeguards, the target has 2× the rows, wall-clock is unchanged, and one pod's
work was pure waste.

### Risk B — Skipped work
Ownership of a file is ambiguous. Pod-1 crashes mid-run; pod-2 assumes pod-1 has it;
neither finishes. Rows are dropped.

### Risk C — Divergent restart
A job started on pod-1 must be restartable from pod-3 after pod-1 dies. If pod-1's restart
state is in its local memory or ephemeral disk, restart on pod-3 replays from scratch —
which by itself might be fine (idempotent writer), but it can also confuse
`transactions` vs `ingest_errors` accounting.

---

## 2. The three invariants we must maintain

1. **Exactly-one-worker-per-partition.** A file (CSV/Parquet) or an id-range slice (DB) is
   owned by exactly one worker step execution at a time.
2. **Global JobRepository.** Every pod agrees on the state of every JobInstance and every
   StepExecution, so restart, stop, and status calls work regardless of which pod handles
   the HTTP request.
3. **Idempotent target write.** Even under the ugliest race — two pods briefly write the
   same row — the target ends up correct. `INSERT … ON CONFLICT DO NOTHING` already gives
   us this; do not weaken it.

If any of the three invariants breaks, one of the risks in §1 becomes real.

---

## 3. Deployment shapes and how each holds the invariants

### Shape 0 — Baseline: one pod (the POC as shipped)
- **Invariant 1:** trivially held; one JVM, one `TaskExecutorPartitionHandler`.
- **Invariant 2:** the `BATCH_*` tables are in the shared Postgres, so restart already works
  across pod restarts.
- **Invariant 3:** held by the writer's `ON CONFLICT`.

Nothing to add. This is where the code stands today.

### Shape 1 — N pods, active/passive (leader election)
Simplest scale-out. All pods start; one is elected the batch leader; the others sit idle.
- Use K8s leader election (e.g. via the `leaderelection` API or a lease `ConfigMap`) or
  a Postgres advisory lock.
- The follower pods still expose `/jobs/executions/{id}` for status reads (they read the
  shared JobRepository), and they still serve `/actuator/*`.
- **Pros:** zero coordination logic in the batch code. Failover is a leader change.
- **Cons:** wall-clock is bounded by one pod's CPU.
- **Verdict:** the right first step. All three invariants hold with no code change.

### Shape 2 — N pods, active/active with the same shared JobRepository
Every pod can accept `POST /jobs/{name}`. Two concurrent launches of the *same job name* on
different pods must not both proceed.
- **Guard with a distinct `JobInstance` per intent.** Our `JobLauncherService` already adds
  `launchedAt=<epoch millis>` so two clicks a millisecond apart get different instances —
  that means Spring Batch will happily run *both* in parallel, which is usually not what you
  want.
- To make "one active run per job name" a real constraint, add an app-level check:
  before launching, query `JobExplorer.findRunningJobExecutions(jobName)` and refuse if any
  are running. Backstop it with a Postgres unique index or advisory lock keyed on the job
  name so the check + insert is atomic across pods.
- Even with that guard, **partition handling is still in-JVM** — the pod that started the
  job runs all its worker threads. Other pods can start *other* jobs.
- **Invariant 1 & 2:** hold as long as the "one active run per job name" guard is in place.
- **Invariant 3:** held by the writer.
- **Verdict:** more concurrency than Shape 1 (multiple different jobs on different pods),
  same per-job throughput.

### Shape 3 — N pods, remote partitioning (the real scale-out)
The manager step lives on the pod that received the launch. The **worker steps run on any
pod in the fleet.** Requires swapping `TaskExecutorPartitionHandler` for
`MessageChannelPartitionHandler` over a broker (Kafka, RabbitMQ, or the DB itself).
- The manager writes each partition's `ExecutionContext` to `BATCH_STEP_EXECUTION_CONTEXT`
  *before* dispatching, and publishes a `StepExecutionRequest` message.
- Any worker pod that consumes a message loads the persisted context, runs the worker step,
  and reports the result on a reply channel.
- **The worker holds an exclusive claim on that partition's `StepExecution` via the shared
  JobRepository** — that is what stops two pods from both grabbing the same partition.
- **Invariant 1:** held by the broker's competing-consumers semantics + the JobRepository's
  step-execution row.
- **Invariant 2:** held by the shared JobRepository (same schema, same DB as the app).
- **Invariant 3:** held by the writer.
- **Verdict:** highest throughput, highest operational cost. This is the shape you graduate
  to when Shape 1 or 2 stops keeping up.

---

## 4. What must be centralised (and what must not be)

### Must be centralised — same instance, all pods point at it
- **Spring Batch metadata schema** (`BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`,
  `BATCH_STEP_EXECUTION`, `BATCH_STEP_EXECUTION_CONTEXT`). If pods don't share this, restart
  is broken and stop is best-effort at best.
- **Target `transactions` table** (obvious).
- **`ingest_errors` audit table** — otherwise you get partial audit trails split across pods.
- **The broker (Shape 3 only)** — Kafka or Rabbit. Its replication is your problem, not ours.
- **A cluster-wide clock reference** for `launchedAt`. K8s worker clocks are usually
  NTP-synced within seconds; if they aren't, use `JobRepository`-assigned time and stop
  relying on `Instant.now()` in the launcher.

### Must NOT be centralised — must live per-pod
- **`partitionTaskExecutor`.** Each pod has its own thread pool. Do not try to share.
- **Local scratch space.** If a worker downloads a file to `/tmp` before parsing, that must
  be pod-local; don't reach for a shared PVC unless the reader genuinely needs random access.
- **In-memory caches.** Bean Validation's `Validator`, JDBC connection pool, and Micrometer
  registry are per-pod. That's fine.

---

## 5. Source-shape-specific concerns

### CSV / Parquet — shared file storage
When you scale beyond one pod, the input directory needs to be **the same set of bytes on
every pod that might read it**. Options in order of increasing operational cost:

- **Object storage (S3 / MinIO)** — swap `FilePartitioner` and the readers to use a
  Hadoop/S3 URI. The current code uses `FileSystemResource`; the replacement uses
  `PathResource` + the S3A filesystem or Spring's `ResourceLoader`. Every pod sees the
  same URIs.
- **Read-only PVC** (`accessModes: ReadOnlyMany`) — cheap, works, ties you to whatever
  storage class supports RWX. Fine for POC/staging.
- **Local disk + external orchestrator that decides which pod runs the job** — degenerates
  into Shape 1 (leader-elected).

**Do not** rely on "pod-1 already downloaded the file so pod-2 will find it too." That's
the shape of an outage.

### DB-to-DB — snapshot semantics
`IdRangePartitioner` runs `SELECT MIN(id), MAX(id)` *once*, on the pod that got the launch
request. Rows inserted into `source_transactions` after that query are:

- **inside** an existing range → they get picked up by whichever worker owns that range.
- **beyond `MAX(id)`** → they are silently ignored by this job.

For a stable ingest you want:

- **Read from a snapshot.** In Postgres, wrap the whole job (manager + workers) in a
  `REPEATABLE READ` transaction started with `pg_export_snapshot()` and have every worker
  `SET TRANSACTION SNAPSHOT` to it. This is invasive — the paging reader currently opens
  its own connections — and usually not worth it. Prefer:
- **Use a watermark column.** Add `ingested_at` and range by that instead of `id`. Only
  scan rows with `ingested_at < :startOfRun`. Newer rows are the next run's problem.
- **Or switch to CDC** and stop batching this source entirely.

---

## 6. Kubernetes-specific checklist

- **`replicas: 1` + leader election** is the safest first move (Shape 1).
- **HPA on `batch_job_records_read_total`** is a *bad* signal — the read rate is a lagging
  indicator of an already-running job. Autoscale on queue depth (Shape 3) or CPU, not on
  the batch counter.
- **Set `terminationGracePeriodSeconds`** to at least the chunk duration + connection
  drain time — otherwise a rolling deploy kills a job mid-chunk. The
  `TaskExecutorJobLauncher` is configured to wait 120s on shutdown; K8s must give it
  that time.
- **`preStop` hook** should `POST /jobs/{id}/stop` for every running execution the pod is
  running, then wait. `curl -X POST` in a `preStop.exec` is enough for the POC.
- **`readinessProbe` should not turn off during a batch run.** The job is running in the
  background; the pod is still able to serve `/jobs/executions/{id}`. Point readiness at
  `/actuator/health/readiness`, not at "no jobs running."
- **Pod disruption budget:** `maxUnavailable: 0` for the batch deployment during business
  hours; drop to `maxUnavailable: 1` for night deploys.
- **Persistent storage for `BATCH_*`.** Postgres runs outside K8s or in a StatefulSet
  with real PV. Not in an ephemeral pod.
- **ConfigMap `ingest.grid-size` must be ≤ CPU quota per pod** — otherwise workers
  time-slice and you burn schedule overhead.

---

## 7. VM-fleet checklist (no K8s)

- **Same shared Postgres for JobRepository + target + audit.** Non-negotiable.
- **A single VM is elected the launcher** via keepalived / consul-lock / an advisory lock
  on Postgres. Others are hot standbys.
- **The launcher VM's disk is not the source-of-truth for input files.** Same rules as K8s
  — mount an NFS share or read from object storage.
- **Deploy the same JAR to every VM**; the launcher role is a config toggle, not a
  different artifact. Otherwise you get "the launcher-VM upgrade got skipped" outages.
- **Restart-in-place after a crash** = restart the service; the JobRepository already
  knows what was in flight.

---

## 8. The failure modes to walk through before shipping

Run each scenario against your chosen shape *before* production:

| Scenario | Expected behaviour |
|----------|-------------------|
| One pod dies mid-chunk | Same JobInstance is restartable from another pod; last un-committed chunk is re-read; no duplicates in `transactions` |
| Two pods launch the same job name simultaneously | One wins the "already running" guard; the other returns 409 (or similar). Never both proceed |
| Postgres briefly unreachable | Currently-running chunks fail their commit and roll back; job status goes to FAILED; restart resumes cleanly |
| Broker (Shape 3) unreachable | Manager blocks waiting for workers; a `/jobs/{id}/stop` should still succeed against the JobRepository |
| Same source file is uploaded twice | Second run reads it, hits `ON CONFLICT DO NOTHING` on every row; `ingest_errors` unaffected; wall clock is wasted but data is correct |
| Rolling deploy during a run | `preStop` requests a cooperative stop; job winds down at chunk boundary; new pod picks up via `/restart` on the JobExecution id |

If any of these behaves differently, the fleet is not yet consistent — fix that before
raising the replica count.

---

## 9. Summary — the one-liner

**Push all coordination into the shared JobRepository (Postgres); keep every worker
idempotent; scale first by leader election, then by remote partitioning; and never let
two pods disagree about what "the source" is.**
