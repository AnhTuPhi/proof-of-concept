# es-hybrid-search-poc

> BM25 + kNN vector search + RRF fusion. The pattern behind every "AI-powered search" since 2024.

## What this POC shows

Three search endpoints over the same product catalog, plus a side-by-side comparator:

| Endpoint | Strategy | Best at | Worst at |
|---|---|---|---|
| `/api/v1/search/lexical?q=` | BM25 over `name`, `description` | exact tokens, SKUs, product codes | synonyms, paraphrases |
| `/api/v1/search/knn?q=` | dense_vector kNN over `embedding` | semantic similarity ("car" ≈ "automobile") | exact-term precision |
| `/api/v1/search/hybrid?q=` | both, fused via **RRF** (Reciprocal Rank Fusion) | most queries, real-world | adds latency + vector RAM |

ES 8.8+ has `rrf` in the search API — we use it.

## Embedding pipeline

The POC uses a **stub embedder** by default — a deterministic hash-into-vector function. It lets the whole flow work end-to-end without depending on OpenAI/Cohere/local model setup.

To swap in a real embedder:
1. Implement `EmbeddingClient` against your provider (OpenAI `text-embedding-3-small` is the easy default at 1536 dim — bump the mapping `dims`).
2. Change `embeddings.provider` in `application.yml` from `stub` to your impl.
3. Re-index (mapping change → drop + recreate, or use the alias-swap pattern from `es-zero-downtime-reindex-poc`).

## Run it

```bash
docker compose up -d
mvn spring-boot:run -pl es-hybrid-search-poc
```

```powershell
./scripts/demo.ps1 compare "wireless charging phone"
./scripts/demo.ps1 compare "RTX 4090"   # lexical wins this
./scripts/demo.ps1 compare "something to make coffee"   # kNN wins this
```

## RRF in 30 seconds

You run two rankers (BM25 and kNN). Each returns a ranked list. For each document `d` that appears in either list, its RRF score is:

```
score(d) = 1 / (k + rank_bm25(d))  +  1 / (k + rank_knn(d))
```

…with `k = 60` (the standard). Rank-only — magnitudes of the two underlying scores don't have to match. ES handles this server-side via the `rrf` top-level retriever (ES 8.8+) so you don't have to merge client-side.

## Honest caveats

- **kNN is RAM-heavy.** HNSW graphs sit in JVM memory. Plan for 4× the raw vector size. A million 1024-dim float vectors is ~4 GB on-heap.
- **Re-embedding is expensive.** Changing the embedding model means re-embedding every doc. Lock in your choice before scaling.
- **kNN-only search is worse than BM25 for SKU/code search.** Don't ship "AI search" without BM25 fallback.

## API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/search/lexical?q=` | BM25 only |
| GET | `/api/v1/search/knn?q=` | kNN only |
| GET | `/api/v1/search/hybrid?q=` | RRF over both |
| GET | `/api/v1/search/compare?q=` | Run all three, return top-5 of each |
| POST | `/api/v1/products` | Add a product (auto-embeds via configured embedder) |

## Files

```
es-hybrid-search-poc/
├── README.md
├── pom.xml
├── src/main/java/com/example/espoc/hybrid/
│   ├── Application.java
│   ├── embed/{EmbeddingClient,StubEmbeddingClient}.java
│   ├── model/ProductDoc.java
│   ├── service/{DataLoader,SearchService}.java
│   └── controller/SearchController.java
├── src/main/resources/
│   ├── application.yml
│   └── es/products-mapping.json
└── scripts/demo.ps1
```
