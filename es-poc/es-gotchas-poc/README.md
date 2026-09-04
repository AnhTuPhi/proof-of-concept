# es-gotchas-poc

> Six common ES bugs. Each has `/break` (causes it), `/explain` (says why), `/fix` (resolves it).

## What this POC shows

For each gotcha, a triplet of endpoints under `/api/v1/gotcha/{name}/{action}`:

| Name | Pitfall | Demonstrates |
|---|---|---|
| `mapping-explosion` | Dynamic mapping inflates field count | mapping bloat → cluster state explosion |
| `fielddata-oom` | Sorting/aggregating on a `text` field | quiet fielddata load → heap pressure |
| `id-collision` | Same `_id` overwrites silently | resumed indexer double-applies events |
| `wildcard-prefix` | Leading-wildcard query | scans entire term dictionary, stalls a node |
| `refresh-too-aggressive` | Default refresh during bulk load | 10–100× slower ingest than necessary |
| `heap-too-big` | JVM `-Xmx` > ~30 GB | compressed-oops cliff (config gotcha, not a /break) |

Each gotcha's README block (printed by `/explain`) tells you the symptom, the why, and the fix.

## Run it

```bash
docker compose up -d
mvn spring-boot:run -pl es-gotchas-poc

# Walk through each gotcha
./scripts/demo.ps1 list
./scripts/demo.ps1 run mapping-explosion
./scripts/demo.ps1 run fielddata-oom
./scripts/demo.ps1 run id-collision
./scripts/demo.ps1 run wildcard-prefix
./scripts/demo.ps1 run refresh-too-aggressive
./scripts/demo.ps1 explain heap-too-big
```

## What each scenario does

### `mapping-explosion`
- `/break?keys=1500` — writes a doc with 1500 unique `properties.*` keys under default dynamic mapping. The index's field count climbs to 1500 → blocked at 1000 by `index.mapping.total_fields.limit`.
- `/fix` — recreates with `properties: { type: "flattened" }`. Same input, one field in the mapping.

### `fielddata-oom`
- `/break` — sorts a 10k-doc index by `name` (a `text` field). ES quietly loads fielddata. JVM heap rises.
- `/fix` — recreates the index with a `name.keyword` multi-field; sorts by `name.keyword`. No fielddata.

### `id-collision`
- `/break` — indexes the same `_id` twice with different content. Second write silently overwrites first.
- `/fix` — uses `op_type: create` to force a 409 on duplicate, or `version_type: external` with a monotonic timestamp version.

### `wildcard-prefix`
- `/break?term=phone` — runs `{wildcard: { name: "*phone*" }}`. Watch `took` climb.
- `/fix?term=phone` — runs the same logical query against an n-gram-indexed `name_ngram` field — `match` query, no wildcard.

### `refresh-too-aggressive`
- `/break?count=20000` — bulk-loads 20k docs with `refresh_interval=1s` (default). Times the load.
- `/fix?count=20000` — same load with `refresh_interval=-1` + restored after, force-merge. Reports the speedup.

### `heap-too-big`
- No `/break` — it's a config concern.
- `/explain` — returns the math: JVM compressed-oops boundary is ~32 GB. Beyond that you lose pointer compression and *effective* heap shrinks. Recommendation: cap ES heap at 30 GB; if you need more memory, run two ES nodes per host.

## API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/gotcha` | list of gotcha names |
| GET | `/api/v1/gotcha/{name}/explain` | text explanation of the gotcha |
| POST | `/api/v1/gotcha/{name}/break?...` | trigger the pitfall |
| POST | `/api/v1/gotcha/{name}/fix?...` | apply the resolution |

## Files

```
es-gotchas-poc/
├── README.md
├── pom.xml
├── src/main/java/com/example/espoc/gotchas/
│   ├── Application.java
│   ├── controller/GotchaController.java
│   ├── service/GotchaRegistry.java
│   └── gotchas/
│       ├── Gotcha.java
│       ├── MappingExplosionGotcha.java
│       ├── FielddataGotcha.java
│       ├── IdCollisionGotcha.java
│       ├── WildcardPrefixGotcha.java
│       ├── RefreshGotcha.java
│       └── HeapGotcha.java
└── src/main/resources/application.yml
```
