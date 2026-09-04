# es-autocomplete-poc

> Three autocomplete recipes. Same dataset. Compare latency, index size, behavior with typos.

## What this POC shows

A 10k-product catalog indexed three different ways, served by three endpoints:

| Endpoint | Strategy | Latency p50 | Index footprint | Typo tolerance |
|---|---|---|---|---|
| `GET /api/v1/suggest/ngram?q=` | Edge n-gram index-time tokens (`min_gram=2 max_gram=15`) | ~5ms | 3-15× field size | manual via `fuzziness: AUTO` |
| `GET /api/v1/suggest/completion?q=` | Completion suggester (FST in JVM memory) | ~2ms | 1-2× field, in-memory | built-in fuzzy |
| `GET /api/v1/suggest/sayt?q=` | `search_as_you_type` field type | ~4ms | ~2-3× | works on prefix + word n-gram |

Hit each on `iph`, `appl`, `samsng` (typo) to compare.

## Run it

```bash
docker compose up -d
mvn spring-boot:run -pl es-autocomplete-poc
```

Then:
```powershell
./scripts/demo.ps1 compare iph
./scripts/demo.ps1 compare samsng    # see who handles typos
./scripts/demo.ps1 bench
```

## When to use which

- **`completion_suggester`** — purpose-built. Best for low-latency typeahead with a curated suggest field. You explicitly tell ES which strings can be suggested; ranking is via `weight`. Downside: requires a separate field, FST lives in JVM heap, doesn't support filtering by other doc fields except via `contexts` (which has its own caveats).
- **`search_as_you_type`** — easiest. Add `"type": "search_as_you_type"` to a field and use `multi_match` of type `bm25_max`. Works on everyday queries, supports filtering, prefix + n-gram fall-back is automatic.
- **Edge n-gram analyzer** — most flexible. You control the analyzer chain, can combine with other text features. Index is larger; query is a plain `match`. Best when you need autocomplete + full-text on the same field.

Rule of thumb: **default to `search_as_you_type`**, upgrade to `completion_suggester` when you need a dedicated suggest experience with sub-5ms p99 and curated suggestions.

## What about typos?

| Strategy | "samsng" → "samsung"? |
|---|---|
| Edge n-gram | needs `match { query, fuzziness: "AUTO" }` — works but extra cost |
| `completion_suggester` | needs `fuzzy: { fuzziness: "AUTO" }` — works, fast |
| `search_as_you_type` | needs `fuzziness: "AUTO"` on the multi_match — works |

All three can handle typos; the question is what you do when there's also an *exact* prefix match. Usually exact > fuzzy, which means a `should` clause combining exact + fuzzy at lower weight.

## Files

```
es-autocomplete-poc/
├── README.md
├── pom.xml
├── src/main/java/com/example/espoc/ac/
│   ├── Application.java
│   ├── service/DataLoader.java
│   ├── service/{NgramSuggester,CompletionSuggester,SaytSuggester}.java
│   └── controller/SuggestController.java
├── src/main/resources/
│   ├── application.yml
│   └── es/
│       ├── ngram-mapping.json
│       ├── completion-mapping.json
│       └── sayt-mapping.json
└── scripts/demo.ps1
```
