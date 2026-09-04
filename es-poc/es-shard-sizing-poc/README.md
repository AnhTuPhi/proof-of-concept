# es-shard-sizing-poc

> Shard count math. ILM rollover. Mapping-explosion guardrails. The boring stuff that decides whether your cluster survives.

## What this POC shows

Three things, three endpoints:

### 1. `GET /api/v1/sizing/calculator?dailyGb=10&retentionDays=30&targetShardGb=30`
A small calculator that takes (daily ingest volume, retention, target shard size) and returns:
- Total data over retention window
- Recommended **primary shards per index** for daily-rolling indexes
- Recommended **rollover threshold** (size + age) for ILM
- Whether your math implies a too-many-shards-per-node problem

This is the conversation that prevents `1-shard for 5TB index` and `100-shard for 5GB index` mistakes.

### 2. `POST /api/v1/sizing/ilm/install` — ILM policy + index template + rollover alias
Sets up a complete hot/warm/delete pipeline for an `audit-*` pattern:
- **Hot**: 7 days, max-size 30GB, replicas=1
- **Warm**: after 7d, forced 1 segment, replicas=0
- **Delete**: after 30d

Then `POST /api/v1/sizing/ilm/load?count=10000` writes some events through the rollover alias so you can `GET _cat/indices/audit-*` and watch rollovers happen.

### 3. `POST /api/v1/sizing/explode-mapping?keys=N` — the mapping explosion demo
Indexes one document with `N` unique field names under `properties`. Watch:
- Cluster state grows (`GET _cluster/state | wc -c`)
- Field count climbs (`GET indexname/_field_caps?fields=*`)
- Eventually `Limit of total fields [1000] in index has been exceeded`.

Then `POST /api/v1/sizing/explode-mapping/fix` recreates the index with `dynamic: false` + a `flattened` property and demonstrates how the same input no longer breaks anything.

## The shard sizing rules of thumb

1. **Target 10–50 GB per shard.** Smaller wastes overhead; larger slows queries and recovery.
2. **Per-node shard cap**: roughly `20 × heap_GB` *total* (primaries + replicas).
3. **Don't over-shard small indexes.** A 200 MB index with 5 shards is just overhead.
4. **For time-series, use ILM rollover** — let ES split into new indexes as volume warrants rather than picking a fixed count upfront.

The calculator endpoint codifies these.

## ILM in plain English

```
audit-write (alias)
       │
       ▼
audit-000001  (HOT, replicas=1, write target)
       │   rollover when size>30GB OR age>7d
       ▼
audit-000002  (HOT, becomes the new write target)
audit-000001  (WARM, replicas=0, force-merged to 1 segment)
       │   age > 30d
       ▼
   delete
```

## Run it

```bash
docker compose up -d
mvn spring-boot:run -pl es-shard-sizing-poc

./scripts/demo.ps1 calculator 50 90 30      # 50 GB/day × 90d, 30 GB shards
./scripts/demo.ps1 ilm                       # install + load some events
./scripts/demo.ps1 explode 1500              # break the mapping
./scripts/demo.ps1 explode-fix
```

## Files

```
es-shard-sizing-poc/
├── README.md
├── pom.xml
├── src/main/java/com/example/espoc/sizing/
│   ├── Application.java
│   ├── service/{ShardSizingCalculator,IlmService,MappingExplosionService}.java
│   └── controller/SizingController.java
├── src/main/resources/
│   ├── application.yml
│   └── es/
│       ├── audit-ilm-policy.json
│       ├── audit-template.json
│       ├── exploded-mapping.json
│       └── flattened-mapping.json
└── scripts/demo.ps1
```
