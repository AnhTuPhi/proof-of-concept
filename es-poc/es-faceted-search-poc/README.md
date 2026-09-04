# es-faceted-search-poc

> Sidebar filters that update counts as you select. The canonical multi-select recipe.

## What this POC shows

A products index plus a `/search` endpoint that returns hits + four facets:
- **brand** (terms agg)
- **category** (terms agg)
- **price** (range agg — buckets: <$10, $10-50, $50-200, $200+)
- **rating** (histogram agg, integer buckets 1-5)

The multi-select trick — when the user has filtered to "brand: Apple", the **brand** facet still shows all brands with counts, but every *other* facet only counts Apple products.

This is implemented with `post_filter` + per-facet `filter` aggregations. The POC's `SearchService.search()` is the canonical 50-line implementation; everything else (the controller, DTO) is plumbing.

## Run it

```bash
docker compose up -d
mvn spring-boot:run -pl es-faceted-search-poc
```

```powershell
# Unfiltered — all 10k docs counted in every facet
Invoke-RestMethod 'http://localhost:8108/api/v1/products/search' | ConvertTo-Json -Depth 6

# Filter by brand=Apple — brand facet shows all brands, others count only Apple
Invoke-RestMethod 'http://localhost:8108/api/v1/products/search?brand=Apple' | ConvertTo-Json -Depth 6

# Multi-filter
Invoke-RestMethod 'http://localhost:8108/api/v1/products/search?brand=Apple&category=electronics&minRating=4' | ConvertTo-Json -Depth 6
```

## Why `post_filter` instead of `filter`?

If you put `brand=Apple` in the regular `query`, every aggregation is scoped to Apple products. The brand facet would show only `{Apple: N}` — useless for letting the user *switch* brands.

`post_filter` runs *after* aggregations are computed. So:
- Hits returned to the client are filtered (only Apple products).
- The aggregations were computed *before* the filter, so they see all docs.

But we want the *other* facets (category, price, rating) to reflect "only Apple". So we put the post_filter ALSO inside each non-brand facet as a `filter` aggregation:

```json
"aggs": {
  "brand":    { "terms": { "field": "brand.keyword" } },          // sees everything
  "category": { "filter": {<brand=Apple>}, "aggs": { ... } },     // sees only Apple
  "price":    { "filter": {<brand=Apple>}, "aggs": { ... } },     // sees only Apple
  "rating":   { "filter": {<brand=Apple>}, "aggs": { ... } }      // sees only Apple
}
```

The POC's `buildAggs()` does this generically for any subset of selected filters.

## Response shape

```json
{
  "totalHits": 1234,
  "items": [ { ... } ],
  "facets": {
    "brand":    [ {"key": "Apple", "count": 320}, ... ],
    "category": [ {"key": "electronics", "count": 480}, ... ],
    "price":    [ {"key": "0-1000",  "count": 18}, ... ],
    "rating":   [ {"key": "5", "count": 220}, ... ]
  }
}
```

Frontends typically render each facet as a list of checkboxes with the count. Selecting one re-issues the search with the filter added to the URL; counts update server-side.

## Files

```
es-faceted-search-poc/
├── README.md
├── pom.xml
├── src/main/java/com/example/espoc/facets/
│   ├── Application.java
│   ├── service/{DataLoader,SearchService}.java
│   ├── model/ProductDoc.java
│   ├── dto/SearchResponse.java
│   └── controller/ProductSearchController.java
├── src/main/resources/
│   ├── application.yml
│   └── es/products-mapping.json
└── scripts/demo.ps1
```
