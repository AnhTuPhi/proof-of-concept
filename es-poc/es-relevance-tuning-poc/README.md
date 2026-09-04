# es-relevance-tuning-poc

> Two configurations of the same query. A judged dataset. Real NDCG@10 numbers. Stop guessing whether your boost helps.

## What this POC shows

A small product catalog with:
- **Two named search configs** — `baseline` (plain `match` over `name+description`) and `tuned` (multi_match with field boosts + function_score for popularity decay).
- **A judged dataset** — 30 hand-labeled query→top-N-relevant-docs records.
- **An evaluator** that runs both configs on every judged query and computes **NDCG@10** + **MRR**.

You can ship a "relevance change" as a config tweak, then compare numbers before/after on the same dataset. Most teams skip this step and ship blind.

## Run it

```bash
docker compose up -d
mvn spring-boot:run -pl es-relevance-tuning-poc
```

```powershell
# Run baseline vs tuned
Invoke-RestMethod 'http://localhost:8106/api/v1/eval/run' | ConvertTo-Json -Depth 6

# Search one query under each config and see the ranking
Invoke-RestMethod 'http://localhost:8106/api/v1/search?q=iphone&config=baseline' | ConvertTo-Json -Depth 4
Invoke-RestMethod 'http://localhost:8106/api/v1/search?q=iphone&config=tuned'    | ConvertTo-Json -Depth 4
```

Expected: `tuned` should score higher NDCG@10 than `baseline` on this dataset, because the dataset is constructed so that field weighting and popularity boosting matter.

## The two configurations

### Baseline
```json
{ "match": { "name": "{q}" } }
```
What most people start with. Single field, default BM25.

### Tuned
```json
{
  "function_score": {
    "query": {
      "multi_match": {
        "query": "{q}",
        "fields": ["name^3", "brand^2", "description"],
        "type": "best_fields"
      }
    },
    "functions": [
      { "field_value_factor": { "field": "popularity", "factor": 0.5, "modifier": "log1p", "missing": 1 } }
    ],
    "score_mode": "sum",
    "boost_mode": "multiply"
  }
}
```
- `name^3` — title matches matter most
- `brand^2` — brand matches matter, less than title
- `description` — body matches at default weight
- `function_score` multiplies in a popularity term so a well-known product beats an obscure one when names tie

## Why function_score, not should clauses?

You can boost with `should` clauses (`should: [{ "match": { "popularity": "high" }}]`). Two reasons we don't:
1. **Math you can't see.** `should` adds scores; `function_score` lets you write the scoring formula explicitly.
2. **Field type mismatch.** Popularity is numeric; `should match` on numerics is awkward.

Use `function_score` for numeric / business-signal boosts.

## API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/search?q=&config=baseline\|tuned` | One-off search |
| GET | `/api/v1/eval/run` | Runs both configs on the judged dataset, prints NDCG/MRR |
| GET | `/api/v1/eval/queries` | The judged dataset |
| POST | `/api/v1/eval/judge` | Add or update a judgment (for live tuning) |

## What good tuning looks like in practice

1. **Collect 30-100 judged queries** representative of real user intent. Even rough judgments beat zero.
2. **Define the metric you care about.** For top-N product search, NDCG@10 or MRR is fine.
3. **Make ONE change at a time.** Re-run eval. Compare. Commit only if it improves the metric without hurting any individual query egregiously.
4. **Watch per-query deltas, not just the average.** A change that lifts the average by 5% but tanks 3 queries by 50% is a bad ship.

The POC's eval output includes per-query NDCG so you can see the per-query effect.
