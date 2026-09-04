# Search quality & relevance

> The category nobody owns until users complain.

Three POCs:
- [es-relevance-tuning-poc](../es-relevance-tuning-poc/) — scoring, boosting, A/B harness
- [es-vietnamese-search-poc](../es-vietnamese-search-poc/) — diacritics & tone folding
- [es-autocomplete-poc](../es-autocomplete-poc/) — typeahead patterns

## The shared problem

Every team eventually ships a search box. The default — `match` over a single `text` field — works for the first 100 documents. By the time you have 100,000, it returns wrong answers and you've lost the ability to reason about why.

This category is about **knowing what the engine is doing** and being able to deliberately change it.

## What "relevance" actually means

Relevance is the *ordering* of results, not the *set*. Two queries can return the same documents in different orders. Users only see the top 10. So the entire game is: are the *right* documents in the top 10, in the *right* order, for *this* user's intent.

Three knobs you'll always need:

1. **Lexical match strength** — BM25 by default. Tune `k1`/`b` per field.
2. **Field weighting** — title matches are worth more than body matches.
3. **Business signals** — popularity, recency, in-stock, premium tier.

The POCs show how to wire all three without entangling them (a common mistake: shoving everything into a single `function_score` blob nobody can read in six months).

## The Vietnamese problem specifically

Default analyzers tokenize Vietnamese badly. The user types `cà phê` and `dien thoai` interchangeably; your index has `cà phê` and `điện thoại`. Without folding, you miss most queries.

The POC compares:
- **Built-in `vietnamese`** analyzer (none — there isn't one)
- **ICU folding** with custom char_filter for tone marks (works well, low setup cost)
- **vi-ws-segmenter** plugin (community plugin for Vietnamese word segmentation — best quality, install pain)

Pick based on your accuracy/operational tradeoffs.

## Autocomplete: three patterns, very different tradeoffs

| Pattern | Latency | Index size | Typo tolerance | Right when |
|---|---|---|---|---|
| **Edge n-gram** | low | large (3-15× field size) | manual fuzziness | small dictionary, you control the field |
| **Completion suggester** | very low (FST in memory) | medium | built-in fuzziness | dedicated suggest field, ranked input |
| **search-as-you-type** | low | medium | works on prefix + n-gram | want "good enough" with one mapping |

POC benchmarks all three on the same dataset so you see the numbers.

## What "A/B testing relevance changes" looks like

Most teams ship relevance changes blind. The POC includes:
- A small **judged dataset** (query → expected top-3 docs) in JSON
- A `RelevanceEvalRunner` that computes **NDCG@10** and **MRR** for two configurations
- An endpoint to compare configs side-by-side: `GET /api/v1/eval?config=baseline&config=tuned&query=...`

This isn't optional in production. Without it, every "I tweaked the boost" PR is a gamble.

## Anti-patterns demonstrated (and avoided)

1. **`copy_to` everything into one big field, then `match` it.** Works until you need per-field boosting; then you're stuck.
2. **One giant `function_score` blob with eight functions.** Unreadable, untestable, debugged by re-deploying.
3. **`should` clauses to boost** instead of `function_score` — works, but the score arithmetic surprises you.
4. **Manually deleting a stop word** because one query is bad — silently breaks 200 others.

Each POC's README marks where it's intentionally showing the wrong way before the right way.
