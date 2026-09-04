# es-bulk-indexing-poc

> Four ingest strategies, same dataset, throughput delta of 30-100×.

## What this POC shows

A single benchmark runner loads N synthetic products four different ways and prints `docs/sec` for each:

| Run | Strategy | Expected throughput (single-node, local) |
|---|---|---|
| `single` | One PUT per document | 200–800 docs/sec |
| `bulk-default` | `_bulk` API, default settings, 1000-doc chunks | 5,000–10,000 docs/sec |
| `bulk-tuned` | `_bulk` + `refresh=-1` + `replicas=0` during ingest | 15,000–30,000 docs/sec |
| `bulk-parallel` | Above + 4 parallel ingest threads | 30,000–60,000 docs/sec |

(Numbers depend heavily on disk + JVM; what matters is the *ratios*.)

## Run it

```bash
docker compose up -d
mvn spring-boot:run -pl es-bulk-indexing-poc

# Then drive the benchmarks:
./scripts/demo.ps1 all 100000     # 100k docs in each mode
./scripts/demo.ps1 bulk-tuned 1000000
```

Output:
```
single        100000 docs in 312.0s   →     320 docs/sec
bulk-default  100000 docs in  18.5s   →   5,405 docs/sec
bulk-tuned    100000 docs in   5.1s   →  19,608 docs/sec
bulk-parallel 100000 docs in   2.4s   →  41,667 docs/sec
```

## Why each trick works

### `refresh_interval = -1` during ingest
Default refresh = 1s creates a new Lucene segment every second. With high write rate, you get hundreds of tiny segments per minute → merge pressure during AND after ingest. Setting `-1` disables refresh — you do **one** final refresh + force-merge at the end. Massive win.

### `number_of_replicas = 0` during ingest
With replicas, every doc gets indexed N+1 times. Set to 0, ingest, then bring replicas back up — ES replicates from primary segments rather than re-indexing, which is much faster.

### Bigger batches
Each bulk request has fixed HTTP + JSON parsing overhead. 1k-doc batches amortize it; 5-10k pushes it further. Sweet spot is typically 5-15 MB per request.

### Parallelism = node count
For a single-node cluster, parallelism > 1 helps until you saturate CPU/disk. The POC uses 4 threads by default; tune via `?parallelism=N`.

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/bulk/run?strategy=&count=&parallelism=` | Run one benchmark |
| POST | `/api/v1/bulk/reset` | Drop and recreate the index |
| GET | `/api/v1/bulk/results` | Last results table |
| GET | `/api/v1/bulk/settings/{strategy}` | Show the actual ES settings each strategy applies |

## Things to NOT do (the POC also covers these)

1. **Don't set `refresh_interval=-1` and forget.** You'll have invisible writes forever. Always restore after ingest.
2. **Don't drop replicas to 0 in production unless you accept a temporary durability gap.** For a one-shot import, fine. For a recurring ingest, no.
3. **Don't batch larger than ~50 MB.** ES rejects (`http.max_content_length` is 100 MB by default) and you eat memory on the coordinator.
4. **Don't use `op_type: create` unless you actually want create-only semantics.** It's strictly slower than upsert.
