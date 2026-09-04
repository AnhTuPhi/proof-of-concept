# es-observability-poc

> The tools you use to debug a slow query in production. Wrapped behind endpoints so the demo is a click.

## What this POC shows

A small product index with intentionally-bad queries available, plus three diagnostic surfaces:

| Endpoint | Wraps | Use when |
|---|---|---|
| `POST /admin/slowlog?queryMs=1000` | `PUT /index/_settings` for slow log thresholds | turning slow log on for an index in incident response |
| `GET /admin/hot-threads` | `GET /_nodes/hot_threads` | "the cluster's CPU is pinned, what threads are doing it" |
| `GET /admin/profile?q=` | `_search?profile=true` | "one query is slow, where is the time going" |
| `GET /admin/diagnose` | composite | one-call cluster health snapshot |

Each one returns the raw ES output (no smoothing) and a small editorial note about what to look for.

## Running it

```bash
docker compose up -d
mvn spring-boot:run -pl es-observability-poc
```

```powershell
# Make some hot queries
./scripts/demo.ps1 firehose-bad        # leading-wildcard queries — burn CPU
# then in another shell:
./scripts/demo.ps1 inspect             # hot threads + slow log + profile a sample
```

## What to look for in each tool

### Slow log
After enabling, slow queries land in `logs/<cluster>_index_search_slowlog.log` in the ES container. The log is shard-local, so a "fast on average, slow sometimes" query you'd otherwise miss shows up here.

Watch for:
- The *same* query slow on some shards but not others → skewed shards.
- Queries that take consistently 1-2s → broken index pattern or fielddata-on-text.

### Hot threads
Returns a Java stack trace of the busy threads on each node. The key lines are at the bottom of each thread block — that's where the time is being spent.

Watch for:
- `org.apache.lucene.search.IndexSearcher` — query work (normal)
- `org.elasticsearch.index.search.MultiMatchQuery` doing rewrite for a leading wildcard — bad
- `BulkPrimaryExecutionContext` — write pressure, not query
- `GC` threads dominating — heap pressure, not query

### Profile API
Per-shard, per-clause breakdown of nanoseconds spent. The interesting field is `breakdown` inside each shard's `searches[]`. Look for:
- One shard doing way more work than others → skewed term distribution.
- `score` time » `next_doc` time → expensive scoring (function_score with heavy scripts).
- Massive `match_count` with small response — your query is scanning more than it returns.

## API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/products/search?q=` | normal search |
| GET | `/api/v1/products/wildcard?q=` | leading wildcard — used to *induce* slowness |
| POST | `/admin/slowlog?queryMs=1000&fetchMs=500` | enable slow log on the demo index |
| DELETE | `/admin/slowlog` | disable slow log |
| GET | `/admin/hot-threads` | wraps `_nodes/hot_threads` |
| GET | `/admin/profile?q=...` | profile the search |
| GET | `/admin/diagnose` | one-shot cluster snapshot |

## Files

```
es-observability-poc/
├── README.md
├── pom.xml
├── src/main/java/com/example/espoc/obs/
│   ├── Application.java
│   ├── service/{DataLoader,DiagnosticsService}.java
│   └── controller/{ProductController,AdminController}.java
├── src/main/resources/
│   ├── application.yml
│   └── es/products-mapping.json
└── scripts/demo.ps1
```
