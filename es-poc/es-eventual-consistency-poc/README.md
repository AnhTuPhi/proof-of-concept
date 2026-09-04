# es-eventual-consistency-poc

> The user just created a product and immediately searched for it. For ~1 second, ES returns nothing. Three patterns to fix it, with the tradeoffs.

## What this POC shows

Same write/read pair, four endpoints:

| Endpoint | Behavior | Write latency | Read freshness | Right when |
|---|---|---|---|---|
| `POST /api/v1/products?mode=default` then `GET /api/v1/products/{id}/search` | naive — write, then search. Often misses. | normal (~5ms) | up to refresh_interval (1s default) | almost never |
| `POST /api/v1/products?mode=wait-for` | write with `refresh=wait_for`. Read sees it. | up to ~1s (waits for next refresh) | immediate | most user-facing writes |
| `POST /api/v1/products?mode=force-refresh` | write, then `_refresh`. Read sees it. | ~5ms + refresh cost (~50ms) | immediate | DO NOT do this in bulk |
| `GET /api/v1/products/{id}?mode=read-through` | search ES, if miss → read Postgres + write through. | normal | immediate (sees own write) | when the client knows it just wrote |

Plus a versioning demo for out-of-order events.

## Run it

```bash
docker compose up -d
mvn spring-boot:run -pl es-eventual-consistency-poc

./scripts/demo.ps1 collapse              # show the default-mode race
./scripts/demo.ps1 wait-for
./scripts/demo.ps1 force-refresh
./scripts/demo.ps1 read-through
./scripts/demo.ps1 version-skew          # external versioning rejects stale writes
```

## What you should see

```
mode=default      → write + search returns 0/1 hits depending on timing (race)
mode=wait-for     → 100% hits, but write took 800ms
mode=force-refresh→ 100% hits, refresh cost shows up in JVM stats over many writes
mode=read-through → 100% hits, ES + DB both consulted, ES is back-filled
```

## Where each fails

### `wait_for`
- Fine for single document writes — the user already paid the latency to click "Save".
- **Disastrous for bulk ingest** — every bulk request waits for next refresh = throughput goes from 50k/s to a few hundred/s.

### Force refresh
- Creates a new Lucene segment immediately. Segments accumulate → merges → CPU + I/O.
- Fine for testing. Never in a hot write path.

### Read-through
- Requires the *client* to know it just wrote. Usually communicated via a "freshness" flag the API passes back.
- Adds a DB round-trip on cache miss. Worth it for the post-write UX bump.

## Version-based deduplication

When events arrive out of order (Kafka rebalance, retry storms), naively applying them flips a doc back to an old state. Pattern:

```java
esClient.index(i -> i.index("products").id(id).document(doc)
        .versionType(VersionType.External)
        .version(updatedAtMillis));
```

ES rejects writes whose version is ≤ the current doc's version. The POC's `version-skew` scenario sends three writes out of order; only the newest sticks.

## Files

```
es-eventual-consistency-poc/
├── README.md
├── pom.xml
├── src/main/java/com/example/espoc/cons/
│   ├── Application.java
│   ├── model/{Product,ProductEntity}.java
│   ├── repo/ProductRepository.java
│   ├── service/ProductService.java        ← the four modes
│   └── controller/ProductController.java
├── src/main/resources/
│   ├── application.yml
│   ├── db/migration/V1__create_products.sql
│   └── es/products-mapping.json
└── scripts/demo.ps1
```
