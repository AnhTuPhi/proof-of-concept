package com.example.espoc.facets.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.espoc.common.web.ApiException;
import com.example.espoc.facets.dto.SearchRequestDto;
import com.example.espoc.facets.dto.SearchResponseDto;
import com.example.espoc.facets.dto.SearchResponseDto.Bucket;
import com.example.espoc.facets.model.ProductDoc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

/**
 * Canonical faceted-search implementation with proper multi-select UX.
 *
 * <p>The trick: each facet is computed under a {@code filter} aggregation that excludes the
 * *self* filter. So the brand facet sees all docs (not just selected-brand docs), but the
 * price facet sees the brand-filtered subset.
 */
@Service
public class SearchService {

    private final ElasticsearchClient es;
    @Value("${app.facets.index-name}") private String indexName;

    public SearchService(ElasticsearchClient es) { this.es = es; }

    public SearchResponseDto search(SearchRequestDto req) {
        try {
            SearchResponse<ProductDoc> r = es.search(buildRequest(req), ProductDoc.class);
            return toDto(r);
        } catch (IOException e) {
            throw ApiException.internal("ES_QUERY_FAILED", "facet search failed", e);
        }
    }

    private SearchRequest buildRequest(SearchRequestDto req) {
        Query baseQuery = req.q() == null || req.q().isBlank()
                ? Query.of(q -> q.matchAll(m -> m))
                : Query.of(q -> q.multiMatch(m -> m.query(req.q()).fields("name", "brand", "category")));

        // The "post-filter" — applied to hits but NOT to the baseline aggregations.
        Query postFilter = combinedFilter(req);

        return SearchRequest.of(b -> {
            b.index(indexName)
                    .size(req.size())
                    .query(baseQuery)
                    .trackTotalHits(t -> t.enabled(true));
            if (postFilter != null) b.postFilter(postFilter);
            // For each facet, build a filter agg whose filter is the AND of all OTHER selected filters.
            b.aggregations("brand",    facetAgg(req, "brand",    f -> f.terms(t -> t.field("brand").size(20))));
            b.aggregations("category", facetAgg(req, "category", f -> f.terms(t -> t.field("category").size(20))));
            b.aggregations("price",    facetAgg(req, "price",    f -> f.range(rr -> rr.field("priceCents")
                    .ranges(rg -> rg.to("1000").key("0-10"))
                    .ranges(rg -> rg.from("1000").to("5000").key("10-50"))
                    .ranges(rg -> rg.from("5000").to("20000").key("50-200"))
                    .ranges(rg -> rg.from("20000").key("200+")))));
            b.aggregations("rating",   facetAgg(req, "rating",   f -> f.histogram(h -> h.field("rating").interval(1.0))));
            return b;
        });
    }

    /** Returns an agg wrapped in a filter that excludes the same-facet filter. */
    private Aggregation facetAgg(SearchRequestDto req, String facetName,
                                 java.util.function.Function<Aggregation.Builder, Aggregation.Builder> inner) {
        Query otherFilters = combinedFilterExcluding(req, facetName);
        if (otherFilters == null) {
            // No other filters → bare agg
            return inner.apply(new Aggregation.Builder()).build();
        }
        Aggregation innerAgg = inner.apply(new Aggregation.Builder()).build();
        return Aggregation.of(a -> a
                .filter(otherFilters)
                .aggregations(facetName, innerAgg));
    }

    private Query combinedFilter(SearchRequestDto req) {
        List<Query> filters = collectFilters(req, null);
        if (filters.isEmpty()) return null;
        return Query.of(q -> q.bool(b -> b.filter(filters)));
    }

    private Query combinedFilterExcluding(SearchRequestDto req, String excludeFacet) {
        List<Query> filters = collectFilters(req, excludeFacet);
        if (filters.isEmpty()) return null;
        return Query.of(q -> q.bool(b -> b.filter(filters)));
    }

    private List<Query> collectFilters(SearchRequestDto req, String excludeFacet) {
        List<Query> list = new ArrayList<>();
        if (req.brand() != null && !"brand".equals(excludeFacet))       list.add(Query.of(q -> q.term(t -> t.field("brand").value(req.brand()))));
        if (req.category() != null && !"category".equals(excludeFacet)) list.add(Query.of(q -> q.term(t -> t.field("category").value(req.category()))));
        if (req.priceBucket() != null && !"price".equals(excludeFacet)) list.add(priceBucketQuery(req.priceBucket()));
        if (req.minRating() != null && !"rating".equals(excludeFacet))  list.add(Query.of(q -> q.range(rg -> rg.untyped(u -> u.field("rating").gte(co.elastic.clients.json.JsonData.of(req.minRating()))))));
        return list;
    }

    private Query priceBucketQuery(String bucket) {
        return switch (bucket) {
            case "0-10"  -> Query.of(q -> q.range(rg -> rg.untyped(u -> u.field("priceCents").lt(co.elastic.clients.json.JsonData.of(1000)))));
            case "10-50" -> Query.of(q -> q.range(rg -> rg.untyped(u -> u.field("priceCents")
                    .gte(co.elastic.clients.json.JsonData.of(1000)).lt(co.elastic.clients.json.JsonData.of(5000)))));
            case "50-200"-> Query.of(q -> q.range(rg -> rg.untyped(u -> u.field("priceCents")
                    .gte(co.elastic.clients.json.JsonData.of(5000)).lt(co.elastic.clients.json.JsonData.of(20000)))));
            default      -> Query.of(q -> q.range(rg -> rg.untyped(u -> u.field("priceCents").gte(co.elastic.clients.json.JsonData.of(20000)))));
        };
    }

    private SearchResponseDto toDto(SearchResponse<ProductDoc> r) {
        List<ProductDoc> items = r.hits().hits().stream().map(Hit::source).toList();
        long total = r.hits().total() == null ? 0 : r.hits().total().value();

        Map<String, List<Bucket>> facets = new LinkedHashMap<>();
        for (String name : List.of("brand", "category", "price", "rating")) {
            facets.put(name, extractBuckets(r, name));
        }
        return new SearchResponseDto(total, items, facets);
    }

    private List<Bucket> extractBuckets(SearchResponse<ProductDoc> r, String name) {
        var agg = r.aggregations().get(name);
        if (agg == null) return List.of();
        // facetAgg may have wrapped in a filter; unwrap to find the inner terms/range/histogram
        if (agg.isFilter()) {
            agg = agg.filter().aggregations().get(name);
            if (agg == null) return List.of();
        }
        if (agg.isSterms()) {
            return agg.sterms().buckets().array().stream()
                    .map(bk -> new Bucket(bk.key().stringValue(), bk.docCount())).toList();
        }
        if (agg.isRange()) {
            return agg.range().buckets().array().stream()
                    .map(bk -> new Bucket(bk.key(), bk.docCount())).toList();
        }
        if (agg.isHistogram()) {
            return agg.histogram().buckets().array().stream()
                    .map(bk -> new Bucket(String.valueOf((int) bk.key()), bk.docCount())).toList();
        }
        return List.of();
    }
}
