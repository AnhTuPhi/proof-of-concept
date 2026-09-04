# es-vietnamese-search-poc

> The default ES analyzer butchers Vietnamese. This POC shows three index-time recipes side-by-side and lets you query them on the same dataset.

## What this POC shows

Three indexes from the same 5,000-doc Vietnamese product catalog (`vn_products_*`), one per analyzer strategy:

| Index | Analyzer | Diacritics-insensitive? | Tone-insensitive? | Cost |
|---|---|---|---|---|
| `vn_products_standard` | ES default `standard` | NO | NO | baseline (broken) |
| `vn_products_folded`   | `lowercase` + `asciifolding` | YES | YES | free; built-in |
| `vn_products_icu`      | ICU folding + ICU tokenizer | YES | YES | requires `analysis-icu` plugin |

For the same query (e.g. `dien thoai`), you get different recall on each index. The POC's `/api/v1/products/compare` endpoint queries all three at once and shows the hit count delta.

## Run it

The `analysis-icu` plugin is *not* bundled by default. Either:
- Install it into the running ES container (see below), or
- Set `app.vietnamese.icu-enabled=false` to skip building the ICU index.

```bash
# Install ICU plugin into the container
docker exec espoc-elasticsearch bin/elasticsearch-plugin install --batch analysis-icu
docker restart espoc-elasticsearch
# Wait for green
curl -s 'http://localhost:9200/_cluster/health?wait_for_status=yellow&timeout=60s'

mvn spring-boot:run -pl es-vietnamese-search-poc
```

## Try it

```powershell
# Same query against all three indexes
Invoke-RestMethod 'http://localhost:8105/api/v1/products/compare?q=dien thoai' | ConvertTo-Json
# Expect: standard=0 hits, folded=many, icu=many (and slightly different ranking)

Invoke-RestMethod 'http://localhost:8105/api/v1/products/compare?q=Đà Nẵng' | ConvertTo-Json
Invoke-RestMethod 'http://localhost:8105/api/v1/products/compare?q=ca phe' | ConvertTo-Json
```

## What "diacritics-insensitive" actually means

Vietnamese uses six tones marked with diacritics: `á à ả ã ạ`, and characters like `đ` and `ơ ư` with marks. Users on phones, keyboards without VN layout, or just being lazy, type `ca phe` instead of `cà phê`.

- **Standard analyzer** indexes `cà phê` as two tokens `cà` and `phê`. Query `ca phe` tokenizes to `ca` and `phe` — zero match.
- **ASCII folding** drops the diacritics at *both* index time and query time, so the inverted index has `ca` and `phe`. Query matches.
- **ICU folding** does the same but with Unicode-aware normalization — handles edge cases like `ổ → o` correctly where ASCII folding sometimes splits a composed grapheme.

## When to use which

| Need | Use |
|---|---|
| "Just works" Vietnamese search, no plugins | `asciifolding` (the "folded" index here) |
| Multi-language including Chinese/Korean/Thai | `analysis-icu` (icu_tokenizer + icu_folding) |
| Highest-quality Vietnamese word segmentation | Community `vi-ws-segmenter` plugin (not in POC — install pain, but worth knowing about) |

For most product search at VN companies, the **folded** strategy is the right default. ICU is the upgrade if you ever go multilingual.

## Files

```
es-vietnamese-search-poc/
├── README.md
├── pom.xml
├── src/main/java/com/example/espoc/vn/
│   ├── Application.java
│   ├── config/VnProperties.java
│   ├── service/VnDataLoader.java
│   ├── service/CompareSearchService.java
│   └── controller/ProductSearchController.java
├── src/main/resources/
│   ├── application.yml
│   ├── data/vn-products.json     (5000 sample VN products)
│   └── es/
│       ├── standard-mapping.json
│       ├── folded-mapping.json
│       └── icu-mapping.json
└── scripts/demo.ps1
```
