# batch-ingest-poc

Production-ready Spring Batch POC for high-volume ingestion.

> **Read the "why" first.** The three docs below explain what problem this POC exists to
> solve, how it solves it, and how it scales. This README is the "how to run it" cheat sheet.
>
> - **[ISSUE.md](ISSUE.md)** — the hard ingest problem in one page: volume, restartability,
>   duplicates, bad rows, three source shapes, ops control. Also the explicit non-goals.
> - **[TECHNICAL.md](TECHNICAL.md)** — per-POC: what makes it hard, what invariant we're
>   protecting, solution shape, key tech by responsibility, tech debt to acknowledge.
> - **[CONSISTENCY.md](CONSISTENCY.md)** — what changes (and what doesn't) when you scale
>   from one pod to a K8s fleet or a VM cluster. Leader-election, remote partitioning,
>   what to centralise, what to keep per-pod.
> - **[docs/flow.html](docs/flow.html)** — a standalone HTML explainer of the flow and the
>   key tech. Open it in a browser, no server needed.

- **Java 21**, Spring Boot 3.4.3, Spring Batch 5
- **Chunk-oriented processing** with bean-validation per item and a skip policy
- **Restartable jobs** backed by the Spring Batch metadata schema in Postgres
- **Parallel partitioning** for CSV/Parquet (one partition per file group) and JDBC sources (id-range partitioning)
- **Three ingest sources** wired identically into the same pipeline:
  - `csvIngestJob` &nbsp; — flat-file CSV
  - `parquetIngestJob` — Parquet via parquet-avro
  - `dbIngestJob` &nbsp; &nbsp;— DB-to-DB copy with paging reader
- Synthetic data generator capable of multi-million-row CSV / Parquet / DB seeds
- REST endpoints for launch / restart / stop / status
- Idempotent target writer (`INSERT … ON CONFLICT DO NOTHING`)
- Skip listener that records rejected rows to `ingest_errors` for audit
- Micrometer + Prometheus metrics (`batch.job.duration`, `batch.job.records.*`)
- Flyway migrations for both the target schema and the Spring Batch metadata tables

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│  POST /jobs/csvIngestJob                                                 │
│           │                                                              │
│           ▼                                                              │
│  asyncJobLauncher (TaskExecutorJobLauncher, bounded pool)                │
│           │                                                              │
│           ▼                                                              │
│  csvManagerStep                                                          │
│  ├─ FilePartitioner: scan ./data/csv/*.csv → N partitions                │
│  └─ TaskExecutorPartitionHandler  ──▶  partitionTaskExecutor (N threads) │
│                                          │                               │
│                                          ▼                               │
│                                 csvWorkerStep (chunk=1000)               │
│                                   reader  → ValidatingProcessor          │
│                                          → JdbcBatchItemWriter           │
│                                          → ON CONFLICT DO NOTHING        │
└──────────────────────────────────────────────────────────────────────────┘
```

`parquetIngestJob` and `dbIngestJob` swap only the partitioner and the reader — the processor,
writer, skip policy, and listeners are reused.

### Key files

| File | What it does |
|------|--------------|
| [BatchInfrastructureConfig.java](src/main/java/com/poc/batchingest/config/BatchInfrastructureConfig.java) | JobRepository, async JobLauncher, partition worker executor |
| [IngestProperties.java](src/main/java/com/poc/batchingest/config/IngestProperties.java) | Tunable knobs (chunk size, grid size, skip limit, pool sizes) |
| [FilePartitioner.java](src/main/java/com/poc/batchingest/batch/partitioner/FilePartitioner.java) | Splits a file glob into N partitions, round-robin |
| [IdRangePartitioner.java](src/main/java/com/poc/batchingest/batch/partitioner/IdRangePartitioner.java) | Splits an id range over a source table into N partitions |
| [CsvTransactionReaderFactory.java](src/main/java/com/poc/batchingest/batch/reader/CsvTransactionReaderFactory.java) | Builds the restartable CSV reader per partition |
| [ParquetTransactionItemReader.java](src/main/java/com/poc/batchingest/batch/reader/ParquetTransactionItemReader.java) | Restartable parquet-avro reader |
| [ValidatingTransactionProcessor.java](src/main/java/com/poc/batchingest/batch/processor/ValidatingTransactionProcessor.java) | Bean Validation in the chunk pipeline |
| [TransactionWriters.java](src/main/java/com/poc/batchingest/batch/writer/TransactionWriters.java) | Idempotent JDBC batch writer |
| [RejectedItemListener.java](src/main/java/com/poc/batchingest/batch/listener/RejectedItemListener.java) | Persists skipped items into `ingest_errors` |
| [JobMetricsListener.java](src/main/java/com/poc/batchingest/batch/listener/JobMetricsListener.java) | Micrometer timers + counters per job |

---

## Run it

### 1. Start dependencies

```bash
docker compose up -d postgres prometheus
```

### 2. Build

```bash
mvn -DskipTests package
```

### 3. Start the app

```bash
mvn spring-boot:run
# or
java -jar target/batch-ingest-poc-1.0.0-SNAPSHOT.jar
```

### 4. Generate test data

A tiny [sample CSV](data/samples/sample.csv) is checked in for smoke tests
(copy it into `./data/csv/` first). For real volume, use the generator:

```bash
# 8 CSV files × 100k rows = 800k rows
curl -X POST "http://localhost:8080/data/csv?files=8&rowsPerFile=100000"

# 4 Parquet files × 100k rows = 400k rows
curl -X POST "http://localhost:8080/data/parquet?files=4&rowsPerFile=100000"

# 500k DB source rows
curl -X POST "http://localhost:8080/data/db?rows=500000&batchSize=5000"
```

For a multi-million-row pull, raise the counts (e.g. `files=20&rowsPerFile=500000` for 10M).

### 5. Launch jobs

```bash
# Returns 202 with executionId immediately; job runs on the launcher pool
curl -X POST http://localhost:8080/jobs/csvIngestJob
curl -X POST http://localhost:8080/jobs/parquetIngestJob
curl -X POST http://localhost:8080/jobs/dbIngestJob
```

### 6. Watch progress

```bash
curl http://localhost:8080/jobs/executions/1 | jq

# Spring Boot Actuator
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/prometheus | grep batch_job

# Or open Prometheus at http://localhost:9090
```

### 7. Restart / stop

```bash
# Stop a running execution (cooperative; checked between chunks)
curl -X POST http://localhost:8080/jobs/1/stop

# Restart from where it stopped (same JobInstance, new JobExecution)
curl -X POST http://localhost:8080/jobs/1/restart
```

---

## Restart semantics

| Source | Restart granularity |
|--------|---------------------|
| CSV | Line within the current file (`FlatFileItemReader.linesToSkip`) + current file index (`MultiResourceItemReader`) |
| Parquet | Current file index + rows read in that file (skipped on resume) |
| DB | Page offset within the partition's id range (`JdbcPagingItemReader.saveState=true`) |

All partitions persist independently in `BATCH_STEP_EXECUTION_CONTEXT`, so a kill -9 mid-run
will resume the unfinished partitions and skip the completed ones.

---

## Tuning

`application.yml` exposes the production knobs:

```yaml
ingest:
  chunk-size: 1000        # rows per JDBC batch + tx commit
  grid-size: 8            # partition worker count
  skip-limit: 50          # tolerate N bad rows per step before failing
  thread-pool:
    core: 8
    max: 16
    queue: 1000
```

Rules of thumb:

- `chunk-size` ≈ 500–5000 for JDBC writers; larger = fewer commits but bigger rollback on failure.
- `grid-size` ≈ #CPU cores. With small files, you get fewer workers than `grid-size`.
- `thread-pool.core` ≥ `grid-size` so the worker steps don't queue.

---

## Tests

```bash
mvn test
```

Runs two end-to-end tests against H2:

- [CsvIngestJobIntegrationTest](src/test/java/com/poc/batchingest/CsvIngestJobIntegrationTest.java) — generates 4 CSV files × 500 rows, runs the partitioned job, asserts row counts.
- [DbIngestJobIntegrationTest](src/test/java/com/poc/batchingest/DbIngestJobIntegrationTest.java) — seeds 2000 source rows, runs the range-partitioned job, asserts the copy.

---

## What this POC does NOT include (deliberately)

- Remote partitioning over a message broker — only `TaskExecutorPartitionHandler` (in-JVM).
  For multi-node ingest, swap in `MessageChannelPartitionHandler` over Kafka or Rabbit.
  See [CONSISTENCY.md](CONSISTENCY.md) for the shape of that change.
- Schema evolution for Parquet — assumes the producer writes the schema this POC reads.
- Throttling / backpressure for the writer — the JDBC pool size is the implicit limit.
- Auth on the launch endpoints — wire Spring Security before exposing this.

Full list of known limitations is in [TECHNICAL.md](TECHNICAL.md) under each job's
"Tech debt to acknowledge" section and the "Global tech debt" section at the end.
