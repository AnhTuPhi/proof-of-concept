# TECHNICAL.md — How the POC solves the hard parts

This document is the pairing document to [ISSUE.md](ISSUE.md). For each of the three ingest jobs
we describe **what makes it hard**, **what invariant we are protecting**, the **shape of the
solution**, the **key tech by responsibility**, **how each sub-problem is answered**, and the
**tech debt we deliberately left on the table**.

The three jobs share the same skeleton — a manager step partitions the input, worker steps run
chunk-oriented `read → validate → write` pipelines in parallel, and every worker persists its
own restart state.

```
        ┌───────────── shared ─────────────┐
POST /jobs/{name}  ─►  asyncJobLauncher  ─►  managerStep (partition)
                                                │
                                                ▼
                                      workerStep × grid-size
                                      ├─ Reader   (source-specific)
                                      ├─ Processor (Bean Validation) ── shared
                                      ├─ Writer   (JDBC upsert)      ── shared
                                      ├─ SkipListener → ingest_errors ── shared
                                      └─ JobMetricsListener → Prometheus ── shared
        └──────────────────────────────────┘
```

---

## POC #1 — `csvIngestJob`

**Source:** a directory of `.csv` files, one row per line, header on line 1.

### What makes it hard
- A single 10M-row CSV is bigger than heap — cannot be slurped into a `List`.
- Many small files parallelise trivially; one giant file does not.
- Restart granularity has to be **per line inside the currently-open file**, not per file.
- A restart must not re-write rows that already landed in the target.
- Bad rows (unparseable number, malformed date) must not fail the whole job.

### What we are protecting
- The target row count is a function of the *source content*, not of *whether we crashed*.
- The `transactions` table never contains a duplicate `transaction_id`.
- The audit table `ingest_errors` contains one row for every rejected input row, forever.

### Solution shape
- `FilePartitioner` scans the input directory and round-robin distributes files across
  `grid-size` partitions. Each partition gets a comma-separated list of file URIs.
- Each worker step wraps a `MultiResourceItemReader` around a `FlatFileItemReader` — Spring Batch
  persists both the current file index and the current line offset in
  `BATCH_STEP_EXECUTION_CONTEXT`, so a crash mid-file resumes from the exact line.
- Chunk-oriented processing: `chunk-size=1000` items are read, validated, and written in one
  JDBC batch inside one transaction. On rollback we lose 1000 rows of progress, no more.
- `INSERT … ON CONFLICT (transaction_id) DO NOTHING` makes the writer idempotent, so a
  restarted chunk is safe.

### Key tech by responsibility
| Responsibility | Component | File |
|----------------|-----------|------|
| Split work | `FilePartitioner` | [FilePartitioner.java](src/main/java/com/poc/batchingest/batch/partitioner/FilePartitioner.java) |
| Read with restart | `MultiResourceItemReader` + `FlatFileItemReader` | [CsvTransactionReaderFactory.java](src/main/java/com/poc/batchingest/batch/reader/CsvTransactionReaderFactory.java) |
| Validate | `Validator` (Jakarta Bean Validation) | [ValidatingTransactionProcessor.java](src/main/java/com/poc/batchingest/batch/processor/ValidatingTransactionProcessor.java) |
| Write idempotently | `JdbcBatchItemWriter` + `ON CONFLICT DO NOTHING` | [TransactionWriters.java](src/main/java/com/poc/batchingest/batch/writer/TransactionWriters.java) |
| Parallel fan-out | `TaskExecutorPartitionHandler` + `ThreadPoolTaskExecutor` | [BatchInfrastructureConfig.java](src/main/java/com/poc/batchingest/config/BatchInfrastructureConfig.java) |
| Persist restart state | `JobRepository` + `BATCH_STEP_EXECUTION_CONTEXT` | Postgres (Flyway V2) |
| Audit bad rows | `RejectedItemListener` → `ingest_errors` | [RejectedItemListener.java](src/main/java/com/poc/batchingest/batch/listener/RejectedItemListener.java) |
| Metrics | `JobMetricsListener` + Micrometer + Prometheus | [JobMetricsListener.java](src/main/java/com/poc/batchingest/batch/listener/JobMetricsListener.java) |

### How each sub-problem is answered
- **Volume vs memory** — `FlatFileItemReader` streams one line at a time; chunk size caps the
  in-flight buffer at `chunk-size` items.
- **Latency** — the manager fans out `grid-size` workers on `partitionTaskExecutor`.
  Two workers never write the same row: each file is only in one partition.
- **Restartability** — `saveState=true` on the readers writes the file index + line offset
  into the step's execution context. Restart via `POST /jobs/{id}/restart` replays the
  same JobInstance with a new JobExecution, and each partition resumes independently.
- **Duplicates** — the target has `UNIQUE (transaction_id)`; the writer uses `ON CONFLICT
  DO NOTHING`; `assertUpdates=false` accepts the legitimate zero-row updates.
- **Bad rows** — `.faultTolerant().skip(NumberFormatException|DateTimeParseException|
  ConstraintViolationException).skipLimit(N)` tolerates isolated bad rows and fails
  the job the moment more than N pile up.
- **Observability** — `JobMetricsListener` emits `batch_job_duration`, `batch_job_records_read`,
  `batch_job_records_written`, `batch_job_records_skipped` per job.

### Tech debt to acknowledge
- **Small-file skew.** Round-robin is only balanced when file sizes are comparable. A
  200-MB file paired with a 2-MB file in the same partition will drag its worker.
- **Header handling is per-file** (`linesToSkip=1`). A file missing its header will drop
  the first data row silently. A stricter check would validate the header before consuming.
- **No character-encoding fallback.** `encoding=UTF-8`; a Latin-1 file will throw
  mid-stream. Ops would prefer a per-file encoding hint.

---

## POC #2 — `parquetIngestJob`

**Source:** a directory of `.parquet` files, columnar, encoded with an Avro schema the POC hard-codes.

### What makes it hard
- Parquet is columnar and row-group encoded — you cannot "skip to line N" cheaply.
- Restart granularity is coarser than CSV: we can resume at file boundaries and at row counts
  *within* the currently-open file, but re-reading a partial row group is not free.
- No off-the-shelf `ItemStreamReader<T>` for Parquet + Avro; we wrote one.

### What we are protecting
- The target row count is deterministic across restarts *even though the Parquet reader is
  physically re-reading and discarding rows during resume*.
- The Parquet reader never leaks a file handle across a partition.

### Solution shape
- Same `FilePartitioner` as CSV.
- Custom `ParquetTransactionItemReader implements ItemStreamReader<TransactionRecord>`:
  - `open(ExecutionContext)` reads `parquet.fileIndex` and `parquet.rowIndex` and
    fast-forwards by reading and discarding rows from the current file.
  - `update(ExecutionContext)` writes those two keys at every chunk checkpoint.
  - `close()` releases the underlying `ParquetReader`.
- Rest of the pipeline (processor, writer, listeners, skip policy) is the same shared code
  used by CSV.

### Key tech by responsibility
| Responsibility | Component |
|----------------|-----------|
| Split work | `FilePartitioner` (shared with CSV) |
| Read with restart | Custom `ParquetTransactionItemReader` over `AvroParquetReader` |
| Restart state format | Two keys in `ExecutionContext`: `parquet.fileIndex`, `parquet.rowIndex` |
| Everything else | Shared with CSV job |

### How each sub-problem is answered
- **Volume vs memory** — `AvroParquetReader` streams one `GenericRecord` at a time.
- **Latency** — same partitioned fan-out as CSV.
- **Restartability** — file index + row-in-file counter. On resume, we open the same file
  and read-discard N rows before yielding the next one.
- **Duplicates** — same idempotent upsert.
- **Bad rows** — `IllegalArgumentException` (Avro type mismatch) added to the skip list
  alongside the shared exceptions.

### Tech debt to acknowledge
- **Fast-forward on resume is O(rowsAlreadyRead).** For a 5M-row file resumed at row 4.9M
  we spend real time re-reading those 4.9M rows before making progress. A production version
  would checkpoint the row-group offset via `ParquetFileReader` low-level API.
- **Schema is not validated.** The reader assumes the producer wrote exactly the fields we
  read. A missing column throws a `NullPointerException` on the first row and fails the chunk.
- **Timestamp handling is best-effort.** We accept `long millis` or an ISO string; other
  Parquet timestamp encodings (nanos, `INT96`, timezone-tagged logical types) fall over.

---

## POC #3 — `dbIngestJob`

**Source:** the `source_transactions` table in the same Postgres instance. Target: `transactions`.

### What makes it hard
- We can't `SELECT *` into memory. We need paging.
- Two workers must not read the same row — no double-writes, no wasted work.
- The source is a live table; holding a single long-running transaction across the whole
  ingest blocks producers and bloats WAL.
- A restart has to resume paging where the previous run stopped, not scan from row 1.

### What we are protecting
- Every source row is read by **exactly one** worker.
- The source-side cursor is short-lived per page, not held open across the whole partition.
- The target upsert is idempotent so a re-read page is still safe.

### Solution shape
- `IdRangePartitioner`: `SELECT MIN(id), MAX(id) FROM source_transactions`, split evenly
  into `grid-size` `[minId, maxId]` buckets.
- Each worker uses a `JdbcPagingItemReader` with `WHERE id BETWEEN :minId AND :maxId`
  and `ORDER BY id ASC` — deterministic pagination guarantees a row is read once.
- `pageSize` = `fetchSize` = 5000 (tuneable); paging opens a new statement per page,
  no long-running cursor.
- `saveState=true` persists the page offset within the partition's range in
  `BATCH_STEP_EXECUTION_CONTEXT`.

### Key tech by responsibility
| Responsibility | Component |
|----------------|-----------|
| Split work | `IdRangePartitioner` |
| Read with restart | `JdbcPagingItemReader` + `PostgresPagingQueryProvider` |
| Cursor lifetime | Per-page statement, not per-partition |
| Everything else | Shared with CSV job |

### How each sub-problem is answered
- **Volume vs memory** — paging reads at most `pageSize` rows at a time.
- **Latency** — `grid-size` disjoint id ranges, each read in parallel.
- **Read-once guarantee** — id ranges are disjoint by construction.
- **Restartability** — the paging reader stores the page cursor per partition.
- **Duplicates** — same idempotent upsert; safe against a partial page re-read.

### Tech debt to acknowledge
- **Skewed id space breaks balance.** If ids are sparse (long gaps), the middle partitions
  finish in seconds while the tails carry all the data. Hash-bucket partitioning would fix it.
- **No source-side snapshot.** New rows inserted into `source_transactions` while the job
  runs *may or may not* be picked up depending on whether they fall in an already-scanned range.
  A production version pins to a snapshot (e.g. Postgres `pg_export_snapshot`) or uses CDC.
- **Postgres-specific paging provider.** Portable would use `SqlPagingQueryProviderFactoryBean`.

---

## Cross-cutting: the shared platform pieces

These aren't "one job's tech" — they are the reason the three jobs stay consistent.

### `BatchInfrastructureConfig`
- Extends `DefaultBatchConfiguration` (the Spring Boot 3.x replacement for
  `@EnableBatchProcessing`).
- Publishes a **bounded** `partitionTaskExecutor` (worker fan-out) and a small
  `jobLauncherTaskExecutor` (so `POST /jobs/*` returns `202 Accepted` immediately).
- Publishes an async `TaskExecutorJobLauncher` marked `@Primary` — this is what allows the
  controller to return the execution id without blocking on the job.
- Publishes a `SimpleJobOperator` (Spring Boot 3.4 does not auto-wire one) so we can
  stop/restart from REST.
- `JobRegistrySmartInitializingSingleton` auto-registers every `@Bean Job` so the operator
  can look them up by name for restart.

### Shared writer — `TransactionWriters`
- One `JdbcBatchItemWriter<TransactionRecord>` used by all three jobs.
- SQL is `INSERT … ON CONFLICT (transaction_id) DO NOTHING` — the single line that makes
  restart safe.
- `assertUpdates=false` — a legitimate no-op update on a duplicate must not fail the batch.

### Shared processor — `ValidatingTransactionProcessor`
- Runs Jakarta Bean Validation using the annotations on `TransactionRecord`.
- Throws `ConstraintViolationException` on violation — the step's skip policy converts that
  into a skip + an `ingest_errors` audit row.

### Shared listeners
- `JobMetricsListener` — one Micrometer timer + three counters per job. Scraped by
  Prometheus at `/actuator/prometheus`.
- `RejectedItemListener` — writes rejected rows to `ingest_errors` with `job_name`,
  `step_name`, `partition_id`, the row payload, and the exception class + message.

### REST surface — `JobLaunchController`
| Endpoint | Meaning |
|----------|---------|
| `POST /jobs/{name}` | Fire-and-forget; returns 202 + execution id |
| `POST /jobs/{id}/restart` | Same JobInstance, new JobExecution, partitions resume independently |
| `POST /jobs/{id}/stop` | Cooperative stop; checked between chunks |
| `GET  /jobs/executions/{id}` | Live status + per-step read/write/skip counters |

---

## Global tech debt (applies to the whole POC)

- **Single-node partitioning only.** The `TaskExecutorPartitionHandler` fans out inside one JVM.
  Multi-node scale-out (Kafka / Rabbit remote partitioning) is documented in
  [CONSISTENCY.md](CONSISTENCY.md) but not wired up.
- **No auth on `/jobs/*`.** Anyone with network reach can launch a job. Front with Spring Security
  or a network policy before exposing.
- **`INSERT … ON CONFLICT DO NOTHING` is Postgres syntax.** On Oracle / SQL Server this needs a
  `MERGE`. The DAO layer is one SQL string away from portability.
- **`ingest_errors` grows unboundedly.** No TTL, no partitioning, no rollup. A noisy producer
  will fill this table.
- **Bean Validation is per-row.** Cross-row invariants (e.g. no two BUY sides for the same
  transaction_id) are not checked and cannot be checked in a streaming processor without state.
- **Skip listener writes one row per skip in its own JDBC transaction.** At high skip volume
  this dominates writer time. Batching the audit writes is a follow-up.
- **No dead-letter queue.** `ingest_errors` is inspectable but not re-driveable. Reprocessing a
  fixed source file re-drops the fixed row into the target; the old error row stays as a stale
  historical entry.
- **`launchedAt` param ensures each launch is a new JobInstance.** This is convenient for
  ad-hoc runs but means the "restart the same instance" semantics only apply when the caller
  uses the `/restart` endpoint — not when they re-`POST /jobs/{name}`.
- **The DB paging reader assumes `id` is dense enough that MIN/MAX partitioning is balanced.**
  See the DB-job tech debt for the fix.
