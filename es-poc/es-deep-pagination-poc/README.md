# es-deep-pagination-poc

> Three pagination strategies on a 1M-doc index. Feel the collapse of `from+size` at depth, and the migration to `search_after` and PIT.

## What this POC shows

A single `products` index loaded with **1,000,000 synthetic products**, exposed three ways:

| Endpoint | Strategy | Right when |
|---|---|---|
| `GET /api/v1/products/page?page=N&size=20` | `from + size` | shallow pagination only (≤ 10k) |
| `GET /api/v1/products/scroll?cursor=...&size=20` | `search_after` (live index) | infinite scroll, cursor APIs |
| `GET /api/v1/products/export?cursor=...&size=500` | PIT + `search_after` | exports, must be consistent |

The same dataset, sorted by the same key (`createdAt DESC, id ASC`), so you can compare results directly. Each response carries a `tookMillis` so you can chart latency vs. offset.

## Run it

```bash
docker compose up -d                              # postgres + es + kibana
mvn spring-boot:run -pl es-deep-pagination-poc # starts on :8102
```

The first boot **loads 1M synthetic products** via the bulk API. Takes ~30-60s. Loader skips if the index already exists; force re-load with `POST /admin/reload?count=1000000`.

## Try the demo

```powershell
./scripts/demo.ps1 baseline      # walk through pages 1, 100, 500, 999 with from+size
./scripts/demo.ps1 sweet-spot    # search_after through 1k pages
./scripts/demo.ps1 export        # PIT export, all 1M, ~2.5s
./scripts/demo.ps1 break-it      # hit the from+size wall: page=600, size=20 → error
```

## What you should see

### `baseline`
```
page=1     tookMs=4    items=20
page=100   tookMs=11   items=20
page=500   tookMs=58   items=20
page=999   tookMs=212  items=20   ← already noticeably slow on a one-node cluster
```

Latency grows roughly linearly with `from`. On a multi-shard production cluster, the effect is multiplied by shard count.

### `break-it`
```
HTTP 400: Result window is too large, from + size must be ≤ [10000].
```

ES protects itself. Default `index.max_result_window=10000`. Raising it just postpones the problem.

### `sweet-spot`
```
page=1     tookMs=4
page=100   tookMs=5
page=500   tookMs=5
page=999   tookMs=5
page=10000 tookMs=5    ← flat
```

`search_after` walks the index in sort order — there's no skip cost. Cost is constant per page.

### `export`
1M docs in 500-doc chunks → ~2000 round-trips → ~2.5s total on a local single-node cluster.

PIT (Point-In-Time) gives a *consistent snapshot* across the entire export, even if other writes are happening concurrently.

## Files

```
es-deep-pagination-poc/
├── README.md
├── pom.xml
├── src/main/java/com/example/espoc/pagination/
│   ├── Application.java
│   ├── config/PaginationProperties.java
│   ├── controller/
│   │   ├── ProductPaginationController.java
│   │   └── AdminController.java
│   ├── model/ProductDoc.java
│   ├── service/
│   │   ├── DataLoader.java
│   │   ├── FromSizePaginationService.java
│   │   ├── SearchAfterPaginationService.java
│   │   └── PitExportService.java
│   └── es/ProductIndex.java
├── src/main/resources/
│   ├── application.yml
│   └── es/products-mapping.json
└── scripts/demo.ps1
```

## Migration notes

If you have a deployed app on `from+size` and want to move:

1. **First**: cap your UI at the existing limit. Show users a "narrow your filter" nudge past page 500 (or wherever your numbers say). Most users never paginate that deep.
2. **For UIs that genuinely need it** (e.g. exports, admin tools): build a parallel cursor API and migrate consumers one at a time.
3. **Never raise `index.max_result_window`** to "fix" the symptom. You're moving the cliff, not removing it.
