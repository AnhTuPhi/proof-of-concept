# Architecture overview

## Goals

The suite has two co-equal goals:
1. **Learning** — every POC should teach one specific Elasticsearch pain point clearly enough that a mid-level engineer reads it once and gets the idea.
2. **Production-ready** — the code in each POC is what we'd actually ship. No demo-grade shortcuts, no "in real life you'd…" footnotes. If a thing is wrong in production, the POC says so and fixes it.

The corollary: most POCs ship *two* code paths — the **naive** version (what people write first) and the **correct** version (what they should ship). The contrast is the teaching.

## Repo shape

```
es-poc-suite/                 parent Maven project
├── pom.xml                   BOM + plugin management for all modules
├── docker-compose.yml        single shared infra
├── infra/                    init scripts, ILM policies, mapping templates
├── docs/                     this directory
├── es-common/                shared config + utilities (depends on nothing else)
└── <thirteen POC modules>/   each is a standalone Spring Boot app
```

### Why one repo, not thirteen

Shared infra dominates the friction budget. If each POC carried its own `docker-compose.yml` and its own `ESClient` config, you'd spend more time wiring than learning. One BOM, one infra stack, one config style → cognitive load lives in the *problem* the POC is about.

### Why each POC is its own Spring Boot app

Three reasons:
1. **Isolation** — a bug in `es-bulk-indexing-poc` doesn't break `es-hybrid-search-poc`.
2. **Reading** — `Application.java` plus a controller and a service is the whole story for that POC. No scrolling through other POCs.
3. **Running** — `mvn spring-boot:run -pl <poc>` and you have exactly one app on exactly one port. No "which profile boots what" puzzle.

The cost is some duplication (each POC has its own `@SpringBootApplication`). Worth it.

## Shared infrastructure

| Service | Port | Used by | Notes |
|---|---|---|---|
| Postgres 16 | 5432 | every POC with a DB story | `wal_level=logical` enabled for CDC; one schema per POC |
| Elasticsearch 8.15 | 9200 | every POC | single-node, security off — *not* a production setting, but right for a POC |
| Kibana 8.15 | 5601 | dev tools, debugging | |
| Kafka 3.8 (KRaft) | 9094 (external) | `db-to-es-sync-poc` | no Zookeeper; profile `sync` |
| Kafka UI | 8088 | optional | |
| Debezium Connect 2.7 | 8083 | `db-to-es-sync-poc` "via Connect" mode | profile `sync-full` |
| Redis 7 | 6379 | `es-eventual-consistency-poc` | profile `consistency` |

Boot only what you need:
```bash
docker compose up -d                            # postgres, es, kibana (no profile gated)
docker compose --profile sync up -d             # adds kafka + UI
docker compose --profile full up -d             # everything
```

## Module layout (inside a POC)

```
es-XXX-poc/
├── pom.xml
├── README.md                       problem statement, how to run, what to look for
├── ARCHITECTURE.md                 sequence diagrams, data flow (only if non-trivial)
├── src/main/java/com/example/espoc/XXX/
│   ├── Application.java            @SpringBootApplication
│   ├── config/                     ES client, beans, properties
│   ├── controller/                 REST endpoints to drive the demo
│   ├── service/                    business logic
│   ├── repository/                 JPA + custom ES queries
│   ├── model/                      entities + DTOs
│   └── demo/                       Runners that load data / run scenarios
├── src/main/resources/
│   ├── application.yml
│   ├── db/migration/               Flyway
│   └── es/                         index mappings as JSON
└── scripts/
    └── demo.ps1                    canonical scenario runner (Windows-first)
```

## Port allocation

| POC | Port |
|---|---|
| db-to-es-sync-poc | 8101 |
| es-deep-pagination-poc | 8102 |
| es-zero-downtime-reindex-poc | 8103 |
| es-bulk-indexing-poc | 8104 |
| es-vietnamese-search-poc | 8105 |
| es-relevance-tuning-poc | 8106 |
| es-autocomplete-poc | 8107 |
| es-faceted-search-poc | 8108 |
| es-hybrid-search-poc | 8109 |
| es-eventual-consistency-poc | 8110 |
| es-shard-sizing-poc | 8111 |
| es-observability-poc | 8112 |
| es-gotchas-poc | 8113 |

## ES client choice

We use the **typed Elasticsearch Java client** (`co.elastic.clients:elasticsearch-java`), not Spring Data Elasticsearch.

Why:
- **Type safety**: queries are built from generated types matching the ES DSL exactly. No more `"match"` typed as a string and discovered at runtime.
- **Coverage**: every ES feature ships day-zero in the typed client. Spring Data lags by a release or two.
- **Honesty**: when you want to tune `refresh`, `wait_for_active_shards`, or `routing`, you do it explicitly. Spring Data abstracts these in ways that make perf debugging harder.

The tradeoff is more verbose query building. We mitigate that with small fluent helpers in `es-common`.

For *purely entity-mapped* read/write where you want zero ceremony (e.g. the `es-faceted-search-poc` write side), Spring Data Elasticsearch is fine and we use it there. The suite isn't dogmatic — it picks the right tool per POC.

## Naming conventions

- ES indexes are prefixed with the POC's short code: `pag_orders_v1`, `reindex_products_v3`, `vn_products`.
- Postgres schemas mirror the POC short code: `pagination`, `reindex`, `vietnamese`.
- Kafka topics are also prefixed: `sync.products.changes`.
- HTTP endpoints all live under `/api/v1/...`.

## Common cross-cutting bits (in `es-common`)

- `ESClientConfig` — bootstraps `ElasticsearchClient` from `app.es.uris` property
- `IdGenerators` — ULID generator (`Ulids.create()`), Snowflake (for time-sortable IDs in deep-pagination)
- `ApiException` + `GlobalExceptionHandler` — uniform error responses
- `PageResponse<T>` — uniform pagination envelope (used by every search endpoint)
- `JsonResource` — load index-mapping JSON files from classpath
