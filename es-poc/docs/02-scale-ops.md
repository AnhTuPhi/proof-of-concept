# Scale & ops

> The category where teams actually get burned.

Four POCs:
- [es-deep-pagination-poc](../es-deep-pagination-poc/) — `from+size` → `search_after` → PIT
- [es-zero-downtime-reindex-poc](../es-zero-downtime-reindex-poc/) — alias swap
- [es-shard-sizing-poc](../es-shard-sizing-poc/) — shard math, ILM tiers
- [es-bulk-indexing-poc](../es-bulk-indexing-poc/) — bulk tuning

## The pattern these POCs share

You wrote it the obvious way. It worked. Data grew. Now it doesn't work, and the fix is *structural* — not a config tweak.

Each POC demonstrates the obvious-but-doomed approach, the symptom at scale, the structural fix, and the migration path.

## Deep pagination

The naive `GET /search?page=100&size=20` translates to `from=2000, size=20`. ES coordinates that across every shard, collecting 2020 top hits per shard, sorting them, then discarding 2000. At page 1000 you're collecting 20,020 per shard. At page 10,000, 200,020 per shard. Eventually:

```
{ "error" : "Result window is too large, from + size must be less than or equal to: [10000]" }
```

— and that's ES protecting itself.

### What to use instead

| Approach | Stateless? | Stable across refreshes? | Latency at depth | Right when |
|---|---|---|---|---|
| `from + size` | yes | no | O(from+size) per shard — collapses | shallow pagination only (≤ 10k) |
| `search_after` | yes (the cursor is the tiebreaker) | no — index changes can move you | flat | infinite scroll, cursor-based APIs |
| Point-In-Time (PIT) + `search_after` | yes | **yes** (PIT is a snapshot) | flat | export, analytics, anything that must be consistent |

The POC implements all three on a 1M-row product index. Hit `/api/v1/products/page` vs `/api/v1/products/search-after` vs `/api/v1/products/pit-export` to feel the difference.

### Migration

You can't just switch — `search_after` cursors are opaque, so paginated UIs that expect "jump to page 42" must change. Two options:
1. Cap `from+size` at 10k, redirect deeper to a "narrow your filter" UI nudge. *Most apps actually need this.*
2. Move to cursor-based APIs and rebuild the pagination component. *More work but better UX.*

## Zero-downtime reindex

Once a mapping is created you cannot change most fields. Adding an analyzer to a `text` field, changing field type, switching a multi-field — all require reindex.

### The pattern

```
                ┌──────────────┐
   write/read ──┤ alias: live  │
                └──────┬───────┘
                       │
                       ▼
                ┌──────────────┐         ┌──────────────┐
                │ products_v1  │         │ products_v2  │
                └──────────────┘         └──────────────┘
                       │                        ▲
                       └────  _reindex  ────────┘
                       
After reindex:
                ┌──────────────┐
   write/read ──┤ alias: live  │
                └──────┬───────┘
                       │
                       ▼
                ┌──────────────┐
                │ products_v2  │
                └──────────────┘
   (v1 deleted on next deploy)
```

### The hard parts (the POC shows each)

1. **Writes during reindex.** If you reindex from v1 → v2 over 30 minutes, what about writes that landed in v1 *after* you started? POC uses **dual-write to both indexes** during the migration window (controlled by a feature flag), then flip the alias, then disable dual-write.
2. **Failed flip.** What if the alias swap fails halfway? Use the atomic `_aliases` API with both add+remove in one call. POC shows the contrast with the naive "remove, then add" pattern that has a ~50ms window of zero indexes.
3. **Rollback.** Keep v1 for at least one deployment cycle in case v2 has a bug.

## Shard sizing

The rules of thumb:
- **Aim for 10–50 GB per shard.** Smaller and you waste overhead; larger and queries get slow and recovery painful.
- **Total shards per node**: roughly `20 × heap_GB` is a hard ceiling.
- **Don't oversharding small indexes.** A 100 MB index with 5 primary shards is 5× the cluster overhead for no benefit.

The POC includes a `ShardSizingCalculator` that takes (expected daily volume, retention days, target shard size) and emits index template + ILM policy JSON.

### Mapping explosion

Letting ES auto-create fields from unknown JSON keys is how you wake up to a 50,000-field mapping that won't load. POC shows:
- `dynamic: strict` (recommended for known schemas)
- `dynamic: false` with explicit fields (recommended for evolving schemas)
- `dynamic: runtime` (the middle ground: indexable later, no upfront cost)

### ILM (Index Lifecycle Management)

For time-series-ish data (logs, events, audit), use rollover + hot/warm/cold:
- **Hot**: writes + recent reads; SSDs, more replicas
- **Warm**: older, less-read; HDDs, fewer replicas, force-merged to 1 segment
- **Cold**: rarely read; searchable snapshots
- **Delete**: gone

POC ships an ILM policy + index template + rollover alias for an `audit-*` pattern, plus a demo loader that fills it.

## Bulk indexing

The naive way: one HTTP request per document. Throughput: ~hundreds/sec. Your import takes a week.

The right way: `_bulk` API, batched. Plus these tricks:

| Trick | When | Gain |
|---|---|---|
| `refresh_interval: -1` during ingest | initial loads | 2-5× |
| `number_of_replicas: 0` during ingest, restore after | initial loads | 1.5-3× |
| Batch size 5-15 MB (~5k docs) | always | 5-20× vs single-doc |
| Parallel ingest threads = node count | always | proportional |
| `wait_for_active_shards: 1` | bulk with replicas=0 | small but free |

POC includes `BulkBenchmarkRunner` that loads 1M synthetic products four ways:
1. Single-doc PUT loop
2. Bulk, default settings
3. Bulk, tuned (refresh=-1, replicas=0)
4. Bulk, tuned, parallel

…and prints throughput. The gap is dramatic.
