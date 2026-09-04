# TECHNICAL — how each POC solves its issue

> Companion to [ISSUE.md](./ISSUE.md). Each section: **hard problem → what we protect → solution shape → tech by responsibility → how each sub-problem is solved → acknowledged tech debt**.

Read [architecture.md](./docs/architecture.md) for cross-cutting decisions (why the typed ES client, module layout, port allocation).

---

## Shared stack (all POCs)

| Responsibility | Choice | Why |
|---|---|---|
| Language / runtime | **Java 21** | Virtual threads for I/O-bound bulk paths; records for DTOs. |
| Framework | **Spring Boot 3.4.3** | Matches the DAccount stack; DI + Actuator + Flyway. |
| Source-of-truth DB | **PostgreSQL 16** | `wal_level=logical` for CDC; one schema per POC. |
| Search engine | **Elasticsearch 8.15** | Native `rrf`, PIT, `search_after`, ILM, refresh=wait_for. |
| ES client | **co.elastic.clients:elasticsearch-java** (typed) | Type-safe DSL; day-zero feature coverage. |
| Migrations | **Flyway** | Per-POC `db/migration/` folders. |
| Event bus | **Kafka 3.8 (KRaft)** | Only for sync POC. No Zookeeper. |
| CDC engine | **Debezium 2.7** (embedded) | Demo simplicity; docs call out Connect for prod. |
| Distributed state | **Redis 7** | Only for eventual-consistency POC (session freshness flag). |
| Container runtime | **Docker Compose** | Single `docker-compose.yml`; profile-gated services. |

Rejected / not used: Spring Data Elasticsearch (query DSL too opaque), `RestHighLevelClient` (deprecated).

---

## POC 1 — [db-to-es-sync-poc](./db-to-es-sync-poc/)

### Hard problem
Three write strategies exist to keep ES ≈ DB. Only two are correct. Teams pick the wrong one, ship, then discover ES is silently drifting from Postgres. The failure has no visible error. The root cause (S1, S2, S6 in ISSUE.md) is that ES writes are **not part of the DB transaction** — nothing enforces both-or-neither.

### What we protect
**Data correctness** — every DB row must eventually be reflected in ES with the correct value, or the drift must be detectable and repairable within a bounded window.

### Solution shape
Ship all three side-by-side so you can see them fail (or not) under injected faults:
1. **Dual-write** (naive) — service calls DB then ES; POC lets you inject an ES failure and observe that the DB has data ES will never see.
2. **Transactional outbox + Kafka → ES** — one JPA transaction writes the entity AND an `outbox_event` row; a poller ships outbox rows to Kafka; a consumer indexes to ES with idempotent upsert.
3. **Debezium CDC (embedded) → ES** — Debezium tails Postgres WAL directly, publishes to Kafka, consumer indexes.

### Tech by responsibility
| Layer | Component | Tech | Rationale |
|---|---|---|---|
| HTTP | `SyncDemoController`, `AdminController` | Spring MVC | Drive scenarios, inject faults. |
| Fault injection | `FailureInjector` | Header-driven flags | Non-invasive; test each strategy under identical fault. |
| DB writer | `NaiveProductRepository`, `OutboxProductRepository`, `CdcProductRepository` | Spring Data JPA | One entity per schema; keeps state siloed. |
| Outbox | `OutboxProducer`, `OutboxPoller`, `OutboxConsumer` | JPA + Kafka client | Same-tx write, background drain, at-least-once with idempotent apply. |
| CDC | `DebeziumEngineRunner` | Debezium embedded | No Connect cluster needed for the demo. |
| ES writes | `ProductEsIndexer` | Typed client | Uses `version_type: external` for idempotency. |

### How each sub-problem is solved
- **S1 (dual-write drift)** → dual-write is included as the counter-example. Outbox/CDC paths are the fix; outbox rows persist across ES outages until the indexer drains them.
- **S2 (phantom writes)** → outbox atomically ties the ES notification to the DB commit; if the DB rolls back, no outbox row exists, so nothing propagates.
- **S6 (WAL slot fills disk)** → CDC path documents this in `ARCHITECTURE.md`; `/admin/cdc/offset` exposes the current LSN so ops can alert on lag.

### Acknowledged tech debt
1. **Outbox poller has no leader election.** Fine for one pod; would race in multi-pod. Comments mark where to add `pg_try_advisory_lock`. See [CONSISTENCY.md](./CONSISTENCY.md#outbox-under-multi-pod).
2. **Debezium runs embedded**, not via Kafka Connect. Simpler to demo, but Connect gives you supervised restart, offset topics on Kafka, distributed workers. For prod: use Connect.
3. **No dead-letter topic** on the outbox consumer — a poison message would loop. Prod code needs a DLT + a retry cap.
4. **No schema registry** — payload is JSON. Fine for one service; a fleet needs Avro/Protobuf + registry.

---

## POC 2 — [es-eventual-consistency-poc](./es-eventual-consistency-poc/)

### Hard problem
ES refreshes new segments **every 1 second by default**. A user who writes then immediately reads has a ~500ms window of "just created it but it's not there" (S3, S4). Solutions have very different costs — pick the wrong one and you break bulk throughput.

### What we protect
**Read-your-writes semantics** for user-facing single-doc writes, **without** wrecking the write path for bulk ingest.

### Solution shape
Four modes on the same endpoint, driven by a `mode=` query param:
- `default` — the race, for reference.
- `wait-for` — `refresh=wait_for` on the write. User waits up to 1s, then read always sees.
- `force-refresh` — write then explicit `_refresh`. Correct, but creates a segment per call (do not use in hot paths).
- `read-through` — search ES; on miss, read the DB and back-fill ES.

Plus a **version-skew** demo: external `version_type` with `updated_at` millis rejects out-of-order writes.

### Tech by responsibility
| Layer | Component | Tech | Rationale |
|---|---|---|---|
| API | `ProductController` | Spring MVC | Single write endpoint, mode-selected. |
| Business | `ProductService` | POJO | Holds the four write strategies. |
| DB | `ProductRepository` | Spring Data JPA | Source of truth for read-through fallback. |
| ES | typed client | | Explicit `refresh` param per call — no framework abstraction. |
| Versioning | `VersionType.External` | | Uses `updatedAt.toEpochMilli()` as monotonic version. |

### How each sub-problem is solved
- **S3 (out-of-order)** → every write carries `version_type=external, version=updatedAtMillis`. ES rejects stale versions with 409, which the service treats as success (a newer write already won).
- **S4 (read-your-writes)** → `wait-for` for user-triggered single writes, `read-through` for cases where the client can hint "I just created this."

### Acknowledged tech debt
1. **`wait-for` is unsafe for bulk paths** — POC docs this but doesn't enforce. Prod code should route bulk writes to a separate method with `refresh=false`.
2. **Read-through requires a client-side hint** — the POC uses a query param; a real app might use a session-scoped "freshness token" in Redis. See [CONSISTENCY.md](./CONSISTENCY.md#read-your-writes-under-multi-pod).
3. **No version generator for creates** — first-ever write has no `updatedAt` yet. POC uses `Instant.now()` which is fine for humans but race-prone under high concurrency. Prod: use a monotonic sequence (Snowflake, DB sequence).

---

## POC 3 — [es-zero-downtime-reindex-poc](./es-zero-downtime-reindex-poc/)

### Hard problem
Once created, most ES mapping fields **cannot be changed in place**. Adding a stemmer to `description`, changing a multi-field, switching an analyzer — all require a reindex. Naive reindex drops writes for its duration (S5).

### What we protect
**Availability of the write path** during mapping migrations. Zero failed writes, zero readers seeing "no such index".

### Solution shape
Full alias-swap pattern with dual-write during the migration window:
1. `products` alias points to `products_v1`.
2. Operator hits `/admin/migration/start` → creates `products_v2` with new mapping.
3. Feature flag enables **dual-write** in `ProductWriter` — writes go to both v1 and v2.
4. Background `_reindex` copies v1 → v2. Progress at `/admin/migration/status`.
5. When done, **atomic** `_aliases` remove-v1+add-v2 in one call.
6. Disable dual-write; keep v1 for one deploy window; delete on next migration.

### Tech by responsibility
| Layer | Component | Tech | Rationale |
|---|---|---|---|
| Writes | `ProductWriter` | Typed client + flag | Single write API, dual-target when flag is on. |
| Alias mgmt | `AliasService` | `_aliases` API | Atomic multi-action call — the whole point. |
| Reindex | `ReindexService` | `_reindex` async, task tracking | Long-running; POC polls task API. |
| Migration state | `MigrationState` bean | In-memory + persisted flag | Would be Redis in a real fleet. |

### How each sub-problem is solved
- **S5 (mapping change downtime)** → dual-write captures deltas during reindex; atomic alias swap avoids the "zero-index window" of the naive remove-then-add pattern.

### Acknowledged tech debt
1. **`MigrationState` is in-memory** — a rolling deploy mid-migration would lose the flag. Prod: persist to Redis or DB. See [CONSISTENCY.md](./CONSISTENCY.md#dual-write-flag-under-multi-pod).
2. **No throttling on `_reindex`** — a large reindex can saturate ES CPU. Prod: use `requests_per_second` param.
3. **v1 delete is manual** — safer, but easy to forget. Prod: a scheduled job checks "migration older than N days, alias points at vN+1, safe to delete."

---

## POC 4 — [es-deep-pagination-poc](./es-deep-pagination-poc/)

### Hard problem
`from+size` at depth is fundamentally O(from × shards) memory per query. Past ~10k it hits `Result window is too large` and refuses to run (O1, O2).

### What we protect
**Query resource use** — no single request can consume unbounded coordinator memory, and export workflows must be able to walk millions of docs in a bounded time.

### Solution shape
Same 1M-doc index, three endpoints, one shared sort key (`createdAt DESC, id ASC`):
- `/page` — `from+size` (kept as the counter-example).
- `/scroll` — `search_after` with the last-hit sort tuple as cursor.
- `/export` — Point-In-Time (PIT) + `search_after` for consistent-snapshot exports.

### Tech by responsibility
| Layer | Component | Tech | Rationale |
|---|---|---|---|
| Pagination | `FromSizePaginationService` | Baseline | Show the collapse. |
| Cursor pagination | `SearchAfterPaginationService` | `search_after` | Flat latency at any depth. |
| Export | `PitExportService` | `open_point_in_time` + `search_after` | Snapshot-consistent walk. |
| Data | `DataLoader` | Bulk API | Preload 1M synthetic products on boot. |

### How each sub-problem is solved
- **O1 (collapse past 10k)** → `search_after` has no `from`, no window limit.
- **O2 (linear slowdown)** → `search_after` walks the index in sort order; cost is per-page, not per-depth.

### Acknowledged tech debt
1. **Cursor is base64-encoded JSON of the sort tuple** — leaks index-internal shape. Prod: HMAC-sign or opaque via a Redis-backed cursor table.
2. **No cursor expiry** — a client can hold a stale cursor forever. PIT export solves this (keepalive TTL); `search_after` doesn't. Prod: reject cursors older than N minutes.

---

## POC 5 — [es-bulk-indexing-poc](./es-bulk-indexing-poc/)

### Hard problem
Naive per-doc `PUT` gets ~500 docs/sec. Tuned `_bulk` on the same hardware gets 30,000-60,000 (O3). Teams under-index for weeks because the default is right for writes but catastrophic for loads.

### What we protect
**Ingest throughput** during initial loads and backfills, so a business decision to reindex isn't blocked by "it'll take 3 days."

### Solution shape
`BulkBenchmarkRunner` runs the same N-doc load four ways and prints docs/sec:
1. Single-doc PUT loop.
2. `_bulk`, default settings.
3. `_bulk` + `refresh_interval=-1` + `number_of_replicas=0` for the ingest window.
4. All of #3 plus parallel ingest threads = node count.

### Tech by responsibility
| Layer | Component | Tech | Rationale |
|---|---|---|---|
| Benchmark harness | `BulkBenchmarkRunner` | Java 21 virtual threads | Cheap parallelism for the multi-writer case. |
| Bulk client | typed client `BulkRequest` | | Direct control over batch size + refresh flag. |
| Index settings toggler | `IndexSettingsService` | `_settings` API | Flip refresh/replicas for the load, restore after. |
| Post-load | `_forcemerge?max_num_segments=1` | | Merges tiny segments accumulated during load. |

### How each sub-problem is solved
- **O3 (slow ingest)** → refresh disabled during load kills per-second segment creation; replicas=0 halves the write work; batches amortize HTTP overhead; parallelism saturates CPU.

### Acknowledged tech debt
1. **No back-pressure on the bulk producer** — if ES rejects, the harness retries in place. Prod: exponential backoff, circuit breaker on 429.
2. **No partial-failure handling** — a `_bulk` response can have per-item errors while the overall request is 200. Harness prints them but doesn't retry per-item. Prod: retry retriable items, DLT the rest.
3. **`refresh=-1` and `replicas=0` are not automatically restored** if the JVM dies mid-load. Prod: register a shutdown hook, or use ILM's ingest tier config.

---

## POC 6 — [es-shard-sizing-poc](./es-shard-sizing-poc/)

### Hard problem
Getting shard count wrong is a *deploy-once, suffer-for-months* mistake. Too many small shards → cluster-state bloat. Too few huge shards → slow queries, painful reallocation. Auto-mapping → 50,000-field explosion (O4, O5, O6).

### What we protect
**Cluster health at scale** — bounded cluster-state size, bounded shard-per-node count, no field-count runaway.

### Solution shape
Three surfaces:
1. `ShardSizingCalculator` — given (daily GB, retention, target shard size), emits primary count + rollover thresholds + a warning if the per-node math is bad.
2. `IlmService` — installs an ILM policy + index template + rollover alias for an `audit-*` pattern (hot 7d / warm forced 1 segment / delete 30d).
3. `MappingExplosionService` — `/break` writes 1500 unique field names under `properties.*`, `/fix` recreates with `dynamic: false` + `flattened`.

### Tech by responsibility
| Layer | Component | Tech | Rationale |
|---|---|---|---|
| Math | `ShardSizingCalculator` | POJO | The "20 × heap_GB" ceiling and 10–50 GB per-shard rules, codified. |
| ILM | `IlmService` | `_ilm/policy`, `_index_template`, rollover alias | Hot/warm/delete for time-series. |
| Mapping guard | `MappingExplosionService` | `dynamic: false` + `flattened` | Same JSON input, bounded field count. |

### How each sub-problem is solved
- **O4/O5 (shard count)** → calculator applies the rules of thumb and warns on violation.
- **O6 (mapping explosion)** → `flattened` field type; unbounded keys become one internal field.

### Acknowledged tech debt
1. **Calculator uses rules of thumb, not workload measurements.** For anything past "small/medium production", you need real query latency data on real shard sizes.
2. **ILM policy is a single template.** A polyglot cluster (multiple index families) needs per-template ILM.

---

## POC 7 — [es-vietnamese-search-poc](./es-vietnamese-search-poc/)

### Hard problem
Default `standard` analyzer tokenizes Vietnamese badly. Users type `dien thoai` on an English keyboard; the index has `điện thoại`. Zero hits. 30-40% of VN queries are typed without diacritics (Q2).

### What we protect
**Recall on Vietnamese text** — user intent must match indexed content whether or not they typed the tone marks.

### Solution shape
Same 5,000-doc dataset, three parallel indexes, one per analyzer strategy:
- `vn_products_standard` — baseline (broken).
- `vn_products_folded` — `lowercase` + `asciifolding` char filter. Free, built-in, handles most cases.
- `vn_products_icu` — `icu_tokenizer` + `icu_folding`. Requires `analysis-icu` plugin; better multilingual behavior.

`/api/v1/products/compare?q=...` queries all three and shows hit counts side-by-side.

### Tech by responsibility
| Layer | Component | Tech | Rationale |
|---|---|---|---|
| Loader | `VnDataLoader` | Bulk API | Same JSON into three mappings. |
| Compare | `CompareSearchService` | Parallel search across 3 aliases | Isolate the analyzer effect. |
| Mappings | JSON files under `es/` | Explicit analyzer config | The teaching is in the mapping files. |

### How each sub-problem is solved
- **Q2 (diacritic mismatch)** → folding runs at both index and query time, so `cà phê` and `ca phe` normalize to the same tokens.

### Acknowledged tech debt
1. **No word-segmentation strategy for compound VN terms** — `vi-ws-segmenter` plugin gets you better quality but is install-painful. POC calls it out, doesn't ship it.
2. **ICU plugin must be installed manually** in the ES container. Prod: bake into your ES image.

---

## POC 8 — [es-relevance-tuning-poc](./es-relevance-tuning-poc/)

### Hard problem
"I tweaked the boost, it feels better" is not shippable. Without a judged dataset and a metric, every relevance change is a gamble (Q1).

### What we protect
**The relevance ordering** — the top-N contract with the user. Changes must be measurable, per-query, with an explicit metric.

### Solution shape
Two named configs (`baseline`: plain `match`; `tuned`: `multi_match` with field boosts + `function_score` popularity term), a 30-query judged dataset in `resources/`, an `EvalRunner` that computes NDCG@10 and MRR for both configs on every query.

### Tech by responsibility
| Layer | Component | Tech | Rationale |
|---|---|---|---|
| Query configs | `QueryBuilder` per config | Typed client DSL | Configs are code, not JSON blobs, so refactors are safe. |
| Judged dataset | JSON in `resources/` | Human-curated | 30 queries, top-3 relevant IDs. |
| Metrics | `NdcgCalculator`, `MrrCalculator` | Java | NDCG@10, MRR — standard IR metrics. |
| Compare API | `EvalController` | | Per-query and aggregate output; lets you spot per-query regressions. |

### How each sub-problem is solved
- **Q1 (blind ship)** → PR includes rerun of `/api/v1/eval/run`. Reviewer sees aggregate + per-query deltas.

### Acknowledged tech debt
1. **30 judgments is a starter set.** Real prod needs 300+ from real user query logs.
2. **No learning-to-rank** — this is BM25 + hand-tuned boosts. LTR is out of scope for the POC.

---

## POC 9 — [es-autocomplete-poc](./es-autocomplete-poc/)

### Hard problem
Autocomplete has three plausible techniques with wildly different latency/index-size/typo characteristics. Picking wrong = either slow suggestions or a 3× index bloat (Q3).

### What we protect
**Suggestion latency** — p99 must beat the user's typing speed, or the UX evaporates.

### Solution shape
Same 10k catalog indexed three ways:
- `ngram-mapping.json` — edge n-gram tokens (`min_gram=2, max_gram=15`).
- `completion-mapping.json` — dedicated `completion` field, FST in JVM heap.
- `sayt-mapping.json` — `search_as_you_type` field type.

Three endpoints, one benchmark script.

### Tech by responsibility
| Layer | Component | Tech | Rationale |
|---|---|---|---|
| Mappings | Three JSON files | Explicit analyzers | The teaching lives in the mapping. |
| Suggesters | One service per strategy | Typed client | Each strategy uses a different API. |
| Benchmark | `demo.ps1 bench` | | Measures latency on same queries against each. |

### How each sub-problem is solved
- **Q3 (autocomplete latency)** → data-driven pick. Rule of thumb baked in: default to `search_as_you_type`; upgrade to `completion_suggester` if you need <5ms p99 on a dedicated suggest surface.

### Acknowledged tech debt
1. **No context/scope filters** — real autocomplete often needs "suggest only in-stock" or "suggest only for current locale."
2. **No dedupe / grouping** — the 10 top suggestions may all be variants of the same product.

---

## POC 10 — [es-hybrid-search-poc](./es-hybrid-search-poc/)

### Hard problem
BM25 is great at exact terms, blind to synonymy. kNN is great at semantic match, blind to SKUs. Users want both. Naive concatenation of two ranked lists gives garbage; you need a fusion strategy (Q4).

### What we protect
**Search quality across both intent modes** — literal terms *and* semantic paraphrase — without picking one at the other's expense.

### Solution shape
Three endpoints on the same catalog:
- `/lexical` — BM25 over `name`, `description`.
- `/knn` — `dense_vector` field with HNSW; `EmbeddingClient` embeds the query.
- `/hybrid` — ES 8.8+ `rrf` retriever, k=60. Server-side fusion.

Embeddings: a `StubEmbeddingClient` (deterministic hash-into-vector) is shipped so the demo works offline; swap for OpenAI/local by implementing the interface.

### Tech by responsibility
| Layer | Component | Tech | Rationale |
|---|---|---|---|
| Embedding | `EmbeddingClient` interface | Pluggable | Stub for offline demo; real impl for prod. |
| Vector store | ES `dense_vector` + HNSW | Native | No separate vector DB needed. |
| Fusion | ES `rrf` retriever | Server-side | No client-side merge logic; ES 8.8+ handles it. |
| Compare API | `SearchController.compare` | | Runs all three, returns top-5 of each. |

### How each sub-problem is solved
- **Q4 (semantic miss)** → hybrid fusion. Lexical still wins on `RTX 4090`; kNN wins on `something to make coffee`; RRF keeps both strengths.

### Acknowledged tech debt
1. **Stub embedder is not semantic.** It's a deterministic hash — good enough to prove the wiring, not the quality. Real POC quality requires a real embedder.
2. **HNSW graph lives in JVM heap.** 4× the raw vector footprint. Not documented in shard sizing calc yet.
3. **Re-embedding on model change is expensive.** POC doesn't handle it — see zero-downtime-reindex POC for the mechanism.

---

## POC 11 — [es-faceted-search-poc](./es-faceted-search-poc/)

### Hard problem
Multi-select sidebar filters need the *selected* facet to show all values (so the user can switch), but *other* facets to reflect the selection. Naively putting the filter in `query` collapses all facets to the selected value (Q5).

### What we protect
**Facet count correctness** — the number next to each checkbox must reflect "what would happen if I selected this."

### Solution shape
`SearchService.search()` uses `post_filter` for the hits and injects the filter *only into the non-selected facets* as `filter` aggregations. About 50 lines of typed-client DSL.

### Tech by responsibility
| Layer | Component | Tech | Rationale |
|---|---|---|---|
| Query building | `SearchService.buildAggs()` | Typed client | Programmatic aggs, one per facet. |
| Hit filtering | `post_filter` | | Applied after aggs, so hit set ≠ agg universe. |
| Per-facet scoping | `filter` agg | | Each non-selected facet sees the selected filter set. |

### How each sub-problem is solved
- **Q5 (wrong counts)** → the canonical `post_filter` + per-facet `filter` agg pattern.

### Acknowledged tech debt
1. **Facet field names are hardcoded.** Prod usually needs a facet registry driven by config or search-config API.
2. **No sub-second cache** — facets are cheap on 10k docs, brutal on 100M. Prod: `request_cache` config + agg-level `execution_hint`.

---

## POC 12 — [es-observability-poc](./es-observability-poc/)

### Hard problem
"Search is slow sometimes" is untriageable without ES's introspection tools. Most teams don't know the tools exist (O11).

### What we protect
**Time-to-diagnosis** during a search incident. When a query is slow, we want a Java stack of the busy thread and a per-clause breakdown in one click.

### Solution shape
Wrapper endpoints over the three ES diagnostic APIs, each with a small editorial note about what to look for:
- `/admin/slowlog` — enable/disable slow-log thresholds per index.
- `/admin/hot-threads` — wraps `_nodes/hot_threads`.
- `/admin/profile?q=` — runs a query with `profile: true` and prints the breakdown.
- `/admin/diagnose` — composite cluster health snapshot.

Plus an intentionally-bad `/api/v1/products/wildcard?q=*foo` endpoint so you can *induce* a symptom and use the tools.

### Tech by responsibility
| Layer | Component | Tech | Rationale |
|---|---|---|---|
| Symptom generator | `wildcard` endpoint | Leading-wildcard query | Predictable, controlled slowness. |
| Diagnostic wrapper | `DiagnosticsService` | Typed client low-level API | Passes ES output through with light annotation. |

### How each sub-problem is solved
- **O11 (no visibility)** → each tool has a documented "what to look for" table in the README so a first-time user can act.

### Acknowledged tech debt
1. **Hot-threads must NOT be exposed publicly** — leaks JVM internals. POC docs this; prod should gate behind admin-only auth.
2. **No Prometheus/OpenTelemetry export** — real prod needs metrics on top of ad-hoc introspection.

---

## POC 13 — [es-gotchas-poc](./es-gotchas-poc/)

### Hard problem
Six specific bugs that hit teams following the ES docs faithfully (O6, O7, O8, O9, O10, and the refresh gotcha O3-adjacent). All are silent, all have clean fixes, none get taught in intro tutorials.

### What we protect
**Team knowledge** — the tacit "don't do that" list, made runnable.

### Solution shape
Six named gotchas, each with `/break`, `/explain`, `/fix` triplet:
1. `mapping-explosion` — `dynamic: false` + `flattened`.
2. `fielddata-oom` — sort on `.keyword` sub-field.
3. `id-collision` — `op_type: create` or `version_type: external`.
4. `wildcard-prefix` — n-gram index instead of leading wildcard.
5. `refresh-too-aggressive` — `refresh=-1` during load, then force-merge.
6. `heap-too-big` — no `/break`; docs the compressed-oops cliff at ~32 GB.

### Tech by responsibility
| Layer | Component | Tech | Rationale |
|---|---|---|---|
| Registry | `GotchaRegistry` | Interface + impls | Add a gotcha by adding a class. |
| Each gotcha | Own class | Self-contained `/break`, `/explain`, `/fix` | Reads as a single lesson. |

### How each sub-problem is solved
See ISSUE.md O6-O10; each fix is described inline in the POC.

### Acknowledged tech debt
1. **`fielddata-oom` break is bounded** — real fielddata OOM happens over hours. POC only ramps heap briefly.
2. **`heap-too-big` is docs-only** — the POC can't actually reallocate the JVM heap at runtime. `/admin/heap-config` reads `-Xmx` and warns.

---

## Cross-cutting tech debt (everywhere)

1. **No auth on `/admin/*` endpoints.** Every POC ships them open. Prod: gate behind Spring Security with an admin role.
2. **Single-pod assumptions** in several POCs (outbox poller, migration state, benchmark runner). See [CONSISTENCY.md](./CONSISTENCY.md).
3. **No unified `application-prod.yml`** — every POC has dev defaults only. Prod deploy needs to override refresh, replicas, JVM sizing.
4. **No mapping versioning** except in the reindex POC. Other POCs would benefit from `products_v1` from day one so future migrations use the alias-swap pattern.
5. **Testcontainers integration tests are declared but sparse.** The POCs are demonstrative; test coverage is not the point of the suite.

If you are lifting code out of this suite into production, treat these as the migration checklist.
