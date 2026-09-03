# ISSUE — What this POC is trying to answer

## 1. The problem in one paragraph

We need to load **tens of millions of trade/transaction rows a day** into an OLTP database from
three very different upstream shapes — CSV drops, Parquet extracts, and another database. Doing it
naïvely (one file, one thread, one big SQL insert) either **takes hours**, **crashes the JVM** when
a source file grows, **silently corrupts the target** when the same row is loaded twice, or leaves
us with **no way to resume** after a crash. This POC is the reference answer for how to do it right
inside a Spring Boot service without reaching for Spark / Flink / Airflow.

## 2. The hard sub-problems

Each is a real production failure mode we want to eliminate — not a theoretical concern.

### 2.1 Volume vs. memory
A single ingest can be **millions of rows** and multiple GB. Loading into a `List<T>` and calling
`saveAll` OOMs the JVM. We need **streaming** with bounded memory regardless of file size.

### 2.2 Wall-clock latency
A single-threaded pipeline reads at ~5–20k rows/s. At 10M rows that is **8–30 minutes minimum**
and is bounded by the writer's serialised commit rate. We need **parallelism** — but parallelism
that doesn't fight for the same DB rows.

### 2.3 Restartability after crash
A JVM crash, a `kill -9`, a pod eviction, or a Postgres blip 40 minutes into a 60-minute job
must not force us to start over. We need **exactly-once effective** semantics: pick up where we
left off, don't re-write rows that already landed, don't skip rows that didn't.

### 2.4 Duplicates
The same source file may be delivered twice. A restarted job may re-read a partial commit. We need
the target to **absorb duplicates without error and without double-counting**.

### 2.5 Bad rows in a large batch
On 10M rows, a handful will fail validation (missing column, malformed date, negative quantity).
We can't fail the whole job for 5 bad rows out of 10M — but we also can't silently drop them.
We need a **tolerated-skip policy with an audit trail** and a **fail-fast threshold** so a broken
producer doesn't get quietly ignored.

### 2.6 Three source shapes, one pipeline
CSV, Parquet, and DB-to-DB have different readers, different restart granularities, and different
partitioning strategies. But the **validation, target write, error audit, metrics, and skip
policy** are identical for all three. Duplicating that logic three ways is a maintenance trap.

### 2.7 Observability
When a job takes 40 minutes, "is it stuck or is it working?" must be answerable in under a second
without SSH-ing anywhere. We need **live progress, throughput, skipped-row counts, and
duration per source**, exposed on standard metrics endpoints.

### 2.8 Operator control
Ops needs to **stop** a runaway job (release the source DB back to online traffic), **restart**
a failed one, and **inspect** what happened — over HTTP, not by editing config and redeploying.

## 3. Constraints we accepted going in

- **Java 21 / Spring Boot 3.4**. No Scala, no Spark, no separate cluster.
- **Postgres** for both target and the Spring Batch metadata schema. Idempotency uses
  `INSERT … ON CONFLICT DO NOTHING`; port to `MERGE` on Oracle/SQL Server if needed.
- **One Spring Boot process** for the base POC. Multi-node scale-out is
  addressed in [CONSISTENCY.md](CONSISTENCY.md), not in code.
- **Ops surface = REST + Prometheus**, not a bespoke UI.
- **No streaming (Kafka, CDC) in the base POC** — this is batch. Streaming is called out as
  the natural next hop in [TECHNICAL.md](TECHNICAL.md).

## 4. Explicit non-goals

- **Schema evolution / semantic mapping** — the source and target column sets already align.
  Adding a source column with a new meaning is a separate change.
- **Cross-source deduplication** — a `transaction_id` from CSV and the same id from Parquet
  are treated as the same row (the unique index enforces it). We don't reconcile which source
  "wins" — first writer wins, everyone else is silently ignored.
- **Authentication on `/jobs/*`** — the POC exposes the launch endpoints unauthenticated. Any
  production rollout must gate these with Spring Security or a network policy.
- **Distributed transactions** — the source read and target write are not in one XA transaction.
  We rely on idempotency, not 2PC.
- **Backpressure to the source** — we throttle *ourselves* via chunk size and pool size; we do
  not tell the upstream to slow down. If the source is another DB, we hold a paging cursor open
  for the duration of a partition.

## 5. Success criteria

A POC is "good" if all six statements below hold on the same code:

1. **10M-row CSV ingest completes in single-digit minutes** on a laptop-class machine with the
   default `grid-size=8`, `chunk-size=1000`.
2. **`kill -9` mid-run + restart** lands exactly the same row count as an uninterrupted run,
   with zero duplicates in `transactions`.
3. **A malformed row in each source file** produces one entry in `ingest_errors` per bad row
   and does not fail the job (until `skip-limit` is exceeded, at which point the job fails loudly).
4. **`POST /jobs/{id}/stop`** returns within seconds and the running job winds down cooperatively
   at the next chunk boundary.
5. **`GET /actuator/prometheus`** shows `batch_job_duration_seconds`,
   `batch_job_records_read_total`, and `batch_job_records_written_total` per job.
6. **Adding a fourth source (e.g. JSON-lines)** is a new reader + a new partitioner — the
   processor, writer, listener, metrics, and skip policy are unchanged.

Everything else in the repo (`TECHNICAL.md`, `CONSISTENCY.md`, the flow explainer) exists to
show *how* those six statements are satisfied.
