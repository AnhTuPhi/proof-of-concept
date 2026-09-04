# es-zero-downtime-reindex-poc

> Change a mapping on a live index. Don't drop writes. Don't stop reads.

## What this POC shows

The alias-swap pattern in full:

1. Live traffic goes through alias `products` → `products_v1`.
2. We discover `description` should be analyzed with English stemmer (new mapping requires reindex).
3. Create `products_v2` with the new mapping.
4. **Enable dual-write** so writes go to both v1 and v2 (this catches deltas during reindex).
5. Trigger `_reindex` from v1 → v2 in the background.
6. When reindex finishes, **atomic alias swap** (`remove v1 + add v2` in one call).
7. Disable dual-write.
8. Keep v1 around for one rollback window, then delete.

## Run it

```bash
docker compose up -d
mvn spring-boot:run -pl es-zero-downtime-reindex-poc
```

Then drive a full reindex with concurrent writes:

```powershell
# Terminal 1 — load initial data and start a write firehose
./scripts/demo.ps1 firehose          # ~50 writes/sec, never stops

# Terminal 2 — trigger the reindex; firehose keeps running
./scripts/demo.ps1 reindex
```

You should see:
- `GET /api/v1/products/count` returns the same value in v1 and v2 once reindex completes.
- Zero failed writes during the migration.
- The alias swap happens in < 5ms.
- After the swap, `GET /api/v1/products/search?q=run` returns hits from v2 (English stemmer in action: matches "running", "ran", "runs").

## Failure scenarios it covers

| Scenario | What the POC does |
|---|---|
| Reindex fails mid-run | v2 is partial — we *don't* swap; v1 keeps serving. Re-trigger to resume. |
| Writes during reindex | Dual-write captures them, so v2 catches up before swap. |
| Alias swap fails | Atomic API → either v1 or v2 serves, never zero indexes. |
| New mapping causes errors at index time | Dual-write throws *before* swap → operator sees the problem and aborts. |

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/products` | Create/update a product (goes through `ProductWriter`, dual-write aware) |
| GET | `/api/v1/products/search?q=` | Search through the live alias |
| GET | `/api/v1/products/count` | Count via alias (and both v1/v2 directly) |
| POST | `/admin/migration/start` | Begin the alias-swap migration |
| GET | `/admin/migration/status` | Current step + reindex task info |
| POST | `/admin/migration/swap` | Manual swap (used by `start` automatically when reindex done) |
| POST | `/admin/migration/rollback` | Swap alias back to v1 |
| DELETE | `/admin/migration/v1` | Drop old index after migration window |

## What you should *not* do (and why)

1. **`PUT /products_v1/_mapping` then reload your app** → most mapping changes can't update in place.
2. **`DELETE /products` then `PUT /products`** → there are seconds where the index doesn't exist; writes 404.
3. **`POST /_aliases {remove}; POST /_aliases {add}`** → small window with zero indexes attached to the alias. Use the single-call form with both actions.
4. **Reindex into the same index with `op_type: create`** → "fixes" duplicates but doesn't help with mapping changes.

The right pattern is what's in this POC.
