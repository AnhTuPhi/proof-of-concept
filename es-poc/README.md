# es-poc-suite

Production-grade Elasticsearch demos for Spring Boot 3.4 / Java 21 / PostgreSQL 16.

Thirteen self-contained POCs, each addressing a specific Elasticsearch pain point that bites teams in production. Each POC is runnable on its own, has a focused README, and is wired with realistic infrastructure (Postgres + ES + Kafka + Redis where needed) via a single `docker-compose.yml`.

> The POCs are written as *learning material that you could actually ship*. Code is annotated, configurations are commented, tradeoffs are explicit. No toy-grade shortcuts.

---

## Quick start

```bash
# 1. Boot the shared infra (Postgres, ES, Kibana, Kafka, Redis, Debezium)
docker compose up -d

# 2. Wait ~30s for ES to become green
curl http://localhost:9200/_cluster/health?wait_for_status=yellow&timeout=60s

# 3. Build everything (uses your system Maven 3.9+ — no wrapper is shipped)
mvn clean install -DskipTests

# 4. Run any POC
mvn spring-boot:run -pl es-deep-pagination-poc
```

Kibana is at <http://localhost:5601>, Kafka UI at <http://localhost:8088>, Postgres at `localhost:5432` (user `es_poc`, pwd `es_poc`).

---

## POC index

### Search quality & relevance — *the "why does my search box suck" category*

| POC | Pain point | Status |
|---|---|---|
| [es-relevance-tuning-poc](./es-relevance-tuning-poc/README.md) | BM25 scoring, function_score, field boosting, A/B harness | Full |
| [es-vietnamese-search-poc](./es-vietnamese-search-poc/README.md) | Vietnamese diacritics & tone folding, ICU vs custom analyzers | Full |
| [es-autocomplete-poc](./es-autocomplete-poc/README.md) | Edge n-gram vs completion suggester vs search-as-you-type | Full |

### Scale & ops — *the "we got paged at 3am" category*

| POC | Pain point | Status |
|---|---|---|
| [es-deep-pagination-poc](./es-deep-pagination-poc/README.md) | `from+size` collapse → `search_after` → PIT migration | Full |
| [es-zero-downtime-reindex-poc](./es-zero-downtime-reindex-poc/README.md) | Alias-swap pattern for mapping changes on live indexes | Full |
| [es-shard-sizing-poc](./es-shard-sizing-poc/README.md) | Shard count math, mapping explosion, hot/warm/cold ILM | Full |
| [es-bulk-indexing-poc](./es-bulk-indexing-poc/README.md) | Bulk API tuning: 10–100x throughput on the same hardware | Full |

### Sync & consistency — *the "ES and the DB disagree" category*

| POC | Pain point | Status |
|---|---|---|
| [db-to-es-sync-poc](./db-to-es-sync-poc/README.md) | Dual-write vs outbox+Kafka vs Debezium CDC — three strategies, real drift | Full |
| [es-eventual-consistency-poc](./es-eventual-consistency-poc/README.md) | Read-your-writes after indexing: refresh=wait_for, version checks, read-through | Full |

### Modern / advanced

| POC | Pain point | Status |
|---|---|---|
| [es-hybrid-search-poc](./es-hybrid-search-poc/README.md) | BM25 + kNN vector search with RRF fusion | Full |
| [es-faceted-search-poc](./es-faceted-search-poc/README.md) | Aggregations for sidebar filters (terms, range, nested) | Full |
| [es-observability-poc](./es-observability-poc/README.md) | Slow log, hot_threads, profile API for debugging slow queries | Full |

### Common gotchas

| POC | Pain point | Status |
|---|---|---|
| [es-gotchas-poc](./es-gotchas-poc/README.md) | Mapping explosion, fielddata OOM, `_id` collisions, wildcard prefix, heap > 32GB | Full |

---

## Read these first

- **[ISSUE.md](./ISSUE.md)** — the umbrella problem the suite exists to solve, categorized sub-problems, and which POC owns each fix. Start here.
- **[TECHNICAL.md](./TECHNICAL.md)** — per-POC: hard problem, what we protect, solution shape, key tech by responsibility, how each sub-problem is solved, tech debt to acknowledge.
- **[CONSISTENCY.md](./CONSISTENCY.md)** — what changes when the app runs as N pods behind a k8s Service or across VMs. The seven landmines and their fixes.
- **[demo.html](./demo.html)** — open in a browser. Interactive walkthrough of every POC's flow, key tech, and demo scenario. Zero build; static file.

## Architecture docs

- [Overall architecture](./docs/architecture.md) — module layout, shared infra, why these choices
- [01 Search quality & relevance](./docs/01-search-quality.md)
- [02 Scale & ops](./docs/02-scale-ops.md)
- [03 Sync & consistency](./docs/03-sync-consistency.md)
- [04 Modern & advanced](./docs/04-modern-advanced.md)
- [05 Common gotchas](./docs/05-gotchas.md)

---

## Technology

- **Java 21** with virtual threads where it helps
- **Spring Boot 3.4.3** — matches the VND DAccount stack
- **Elasticsearch 8.15** with the new **co.elastic.clients:elasticsearch-java** client (the typed one, not the deprecated `RestHighLevelClient`)
- **PostgreSQL 16** as the source-of-truth DB
- **Kafka 3.8** (KRaft mode — no Zookeeper)
- **Debezium 2.7** for CDC demos
- **Testcontainers** for integration tests

## Module layout

```
es-poc-suite/
├── pom.xml                    # parent: BOM, plugins, properties
├── docker-compose.yml         # shared infra
├── docs/                      # architecture per pain point
├── es-common/                 # shared config, DTOs, id generators
├── db-to-es-sync-poc/
├── es-deep-pagination-poc/
├── es-zero-downtime-reindex-poc/
├── es-bulk-indexing-poc/
├── es-vietnamese-search-poc/
├── es-relevance-tuning-poc/
├── es-autocomplete-poc/
├── es-faceted-search-poc/
├── es-hybrid-search-poc/
├── es-eventual-consistency-poc/
├── es-shard-sizing-poc/
├── es-observability-poc/
└── es-gotchas-poc/
```

Each POC module is a standalone Spring Boot app. Pick the port from its `application.yml` (8101 → 8113, in POC order).

## Conventions

- All POCs use the same Postgres instance, **different schemas** (`sync`, `pagination`, `reindex`, ...).
- All POCs use the same ES cluster, **different index name prefixes** (e.g. `pag_orders_v1`).
- HTTP API for every POC at `/api/v1/...` — kept tiny, just enough to drive the demo.
- Each POC ships a `scripts/demo.ps1` (Windows-first) to run the canonical scenario.
