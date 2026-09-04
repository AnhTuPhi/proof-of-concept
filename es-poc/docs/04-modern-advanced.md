# Modern & advanced

Three POCs:
- [es-hybrid-search-poc](../es-hybrid-search-poc/) — BM25 + kNN with RRF
- [es-faceted-search-poc](../es-faceted-search-poc/) — aggregations for filters
- [es-observability-poc](../es-observability-poc/) — debugging slow queries

## Hybrid search — what "AI search" actually looks like under the hood

Every "AI-powered search" launched in 2024-2026 (Notion, Slack, Linear, GitHub Code Search) is some flavor of this:

1. Generate a dense vector embedding for the query.
2. Run a **kNN search** against pre-computed embeddings of your documents.
3. **In parallel**, run a normal **BM25 lexical search**.
4. Combine the two ranked lists with **Reciprocal Rank Fusion (RRF)** or a learned re-ranker.

Why both, not just kNN? Two reasons:
- kNN excels at semantic match ("car" → "automobile") but loses exact-term precision ("RTX 4090" → unrelated GPUs).
- BM25 excels at exact terms but is blind to synonymy.

RRF is the cheapest fusion approach. For each result `r`, its RRF score is `1/(k + rank_BM25(r)) + 1/(k + rank_kNN(r))` with `k ≈ 60`. ES 8.8+ has `rrf` natively in the search API.

The POC ships:
- A products index with `name`, `description`, and `description_embedding` (1024-dim `dense_vector`).
- A small embedding endpoint (calls a sidecar — by default it uses a stub that hashes text into a vector, with notes on swapping in OpenAI/Cohere/local models).
- Three search endpoints: `/lexical`, `/knn`, `/hybrid` so you can compare results on the same queries.
- A small judged dataset showing where each approach wins.

### Honest caveats
- **kNN is RAM-heavy**. HNSW graphs live in JVM memory. Plan for 4× the raw vector size in RAM.
- **Re-embedding is expensive**. Changing the embedding model = re-embedding every doc. Plan the model decision carefully.
- **The "kNN is magic" pitch is wrong**. Without a good lexical fallback, kNN-only search is worse than BM25 for product / SKU / code search.

## Faceted search

The sidebar filters on every e-commerce site. "Brand: Apple (32)", "Price: $500-$1000 (12)", "Color: red (8)".

Each facet is an **aggregation** computed over the *same query* but returning bucketed counts.

The POC shows:
- **Terms aggregation** for low-cardinality fields (brand, category).
- **Range aggregation** for price buckets.
- **Histogram aggregation** for things like "ratings 1-2, 2-3, 3-4, 4-5".
- **Nested aggregation** when facets live inside a nested doc (e.g., variants of a product).
- **Filter aggregation** for "show me the count *if* I were to apply this filter" — the standard sidebar UX where unselected counts update as you select.

The hard part is the **multi-select facet UX**: when the user selects "Brand: Apple", the brand facet itself should still show *all* brands (not just Apple), but the price facet should only count Apple products. ES handles this with `post_filter` + per-facet `filter` aggregations. The POC has the canonical recipe.

## Observability — debugging slow ES queries

You don't reason about ES performance by guessing. You use the tools:

### Slow log

ES writes a query to a separate log when it crosses a threshold. Configure per-index:

```json
PUT products/_settings
{
  "index.search.slowlog.threshold.query.warn": "1s",
  "index.search.slowlog.threshold.fetch.warn": "500ms"
}
```

The POC exposes this via a `/admin/slowlog` endpoint that sets/unsets thresholds.

### Hot threads

When a node is hot, this tells you *which threads* are doing the work:

```
GET _nodes/hot_threads
```

Output is a Java stack trace of the busy threads. POC wraps this as `/admin/hot-threads` (don't expose this in production).

### Profile API

Per-query breakdown of what each shard spent time on:

```json
GET products/_search
{
  "profile": true,
  "query": { "match": { "name": "iphone" } }
}
```

POC has `/admin/profile?q=...` that runs the query and pretty-prints the breakdown: which clauses, which Lucene scorers, how many docs each visited. This is how you discover that one shard is doing 1000× the work because of a skewed term.

### What to look for

| Symptom | Likely cause | Tool |
|---|---|---|
| Some queries slow, most fast | Skewed shards | profile API |
| All queries slow recently | Heap pressure → GC | `_nodes/stats` JVM section |
| Bulk indexing is slow | refresh too aggressive | `_nodes/stats` indexing.refresh |
| `wildcard` query times out | Leading wildcard | slow log → catch the pattern |
| Cluster yellow/red | Shards not allocated | `_cluster/allocation/explain` |

POC's `/admin/diagnose` endpoint runs a small battery of these and emits a Markdown report.
