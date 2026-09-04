# Common ES production gotchas

One POC, six demos: [es-gotchas-poc](../es-gotchas-poc/)

Each gotcha follows the same shape: a `/api/v1/gotcha/<name>/break` endpoint that *causes* the problem (in a controlled, recoverable way), a `/explain` that describes what just went wrong and why, and a `/fix` that demonstrates the resolution.

## Gotcha 1 — Mapping explosion

**The setup**: dynamic mapping is on (the default). Your app logs analytics events with arbitrary `properties.*` keys. A buggy producer starts emitting `properties.user-{uuid}: true` for every request. Each unique key becomes a field.

**The symptom**: after a few hours, the mapping has 80,000 fields. Cluster state grows. Recovery time becomes minutes. Eventually:

```
{ "error": "Limit of total fields [1000] in index has been exceeded" }
```

**The fix**: use `dynamic: false` or `dynamic: strict` on objects with unbounded key cardinality. Or use `flattened` field type — stores the whole sub-object as a single field internally.

POC: hits `/break` to crank up the field count, `/explain` shows the mapping size, `/fix` swaps to `flattened`.

## Gotcha 2 — Fielddata on text fields

**The setup**: you `sort` or `agg` on a `text` field. ES quietly loads **fielddata** — a per-document inverted-of-inverted index that holds all tokens in JVM heap.

**The symptom**: heap usage climbs and never comes back down. Eventually OOM.

**The fix**: never sort/agg on `text`. Use a `keyword` sub-field (the default `.keyword` multi-field is exactly this). If you need both fulltext and sortable: `"name": {"type": "text", "fields": {"keyword": {"type": "keyword"}}}` and sort on `name.keyword`.

POC: `/break` sorts on a `text` field, watch JVM heap rise via `_nodes/stats`.

## Gotcha 3 — `_id` collisions when supplying your own IDs

**The setup**: you've decided to use your own primary key as ES `_id`. Two writes with the same `_id` *overwrite* each other (an update, not a duplicate). You discover this when your indexer process crashed and resumed from an older checkpoint, double-processing some events.

**The symptom**: documents have older content than expected. Hard to debug because there's no version to compare against.

**The fix**: use `op_type: create` to force a 409 on duplicate `_id`, or use `version_type: external` with a monotonic version (e.g. `updated_at` millis) so older writes are rejected.

POC: `/break` indexes the same doc twice with conflicting content; `/fix` adds the version check.

(This is also why the suite has `IdGenerators.ulid()` — ULIDs are time-sortable, so even in unlucky concurrent inserts you don't get a literal collision.)

## Gotcha 4 — Wildcard prefix queries

**The setup**: a user types `*example` into the search box. Your code does `{"wildcard": {"name": "*example"}}`.

**The symptom**: that query scans every term in the inverted index. On a 100M-doc cluster, one such query stalls the whole node.

**The fix**: never allow leading wildcards. Either:
- Use a `reverse` token filter to index reversed copies, then query as a non-leading wildcard.
- Use `n-gram` analyzer at index time (POC also covers this in `es-autocomplete-poc`).
- Reject leading-wildcard queries at the API layer.

POC: `/break` runs a leading-wildcard query against a small index and shows query time; `/fix` shows the n-gram alternative.

## Gotcha 5 — Refresh interval too aggressive during bulk loads

**The setup**: you're loading 10M documents via bulk API. Throughput is 800 docs/sec. Should be 10,000+.

**The cause**: default `refresh_interval=1s` means ES is creating ~tens of tiny segments per second, then merging them later. Each refresh is overhead. Each tiny segment slows search.

**The fix**: during ingest, `PUT /index/_settings {"index.refresh_interval": "-1"}` and `"number_of_replicas": 0`. After, restore both and force a merge:

```json
POST /index/_forcemerge?max_num_segments=1
```

POC: `/break` runs the slow path, `/fix` runs the tuned path. (This is also covered in `es-bulk-indexing-poc` — the gotchas POC is the elevator-pitch version.)

## Gotcha 6 — Java heap > 32 GB hits compressed-oops cliff

**The setup**: you set `-Xms64g -Xmx64g` thinking "more is better".

**The cause**: JVM uses **compressed object pointers** (32-bit pointers + scaling) below ~32 GB heap, halving pointer size and improving cache density. Above that boundary it falls back to 64-bit pointers and effective memory available drops, sometimes *below* what you'd have at 30 GB.

**The fix**: keep ES JVM heap ≤ 30 GB (some safety margin below the ~32 GB compressed-oops cap). If you need more memory, run **two ES nodes** on the box instead of one big one.

POC: no /break for this one — it's a config concern. The POC ships a `/admin/heap-config` endpoint that reads the current `-Xmx` and warns if it crosses 30 GB, plus a docs page with the math and the two-node recipe.

## Why these specific six

These are the gotchas that:
1. Are silent until they bite hard (no warning before the OOM / state explosion).
2. Look like a setup mistake but are actually default-config mistakes.
3. Have a clean, one-line fix once you know.

Other gotchas worth mentioning that *aren't* in the POC (because they're either rare, well-documented, or have no clean demo):
- Cross-cluster search latency cliffs.
- `fielddata_breaker` rejecting requests at boundary conditions.
- `discovery.seed_hosts` misconfiguration leading to split brain (ES 7+ much harder, but still possible in cloud rolling restarts).
- Snapshot repository corruption (write-once-then-forget bucket policies).

If you want any of those added, they slot in naturally as gotcha 7+.
