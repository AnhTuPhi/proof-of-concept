package com.example.espoc.ac.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.CompletionSuggestOption;
import com.example.espoc.common.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
public class SuggestService {

    public record Suggestion(String text, String id, long tookMs) {}
    public record SuggestionList(String strategy, long tookMs, List<String> suggestions) {}

    private final ElasticsearchClient es;
    @Value("${app.autocomplete.ngram-index}")      private String ngramIndex;
    @Value("${app.autocomplete.completion-index}") private String completionIndex;
    @Value("${app.autocomplete.sayt-index}")       private String saytIndex;

    public SuggestService(ElasticsearchClient es) { this.es = es; }

    public SuggestionList ngram(String q, int size) {
        try {
            SearchResponse<Map> r = es.search(s -> s
                    .index(ngramIndex)
                    .size(size)
                    .source(src -> src.filter(f -> f.includes("name")))
                    .query(qq -> qq.match(m -> m.field("name").query(q).fuzziness("AUTO"))), Map.class);
            return new SuggestionList("ngram", r.took(),
                    r.hits().hits().stream().map(h -> (String) ((Map) h.source()).get("name")).toList());
        } catch (IOException e) {
            throw ApiException.internal("ES_QUERY_FAILED", "ngram suggest failed", e);
        }
    }

    public SuggestionList completion(String q, int size) {
        try {
            SearchResponse<Map> r = es.search(s -> s
                    .index(completionIndex)
                    .suggest(sg -> sg.suggesters("product", sgg -> sgg
                            .prefix(q)
                            .completion(c -> c.field("suggest").size(size).skipDuplicates(true)
                                    .fuzzy(f -> f.fuzziness("AUTO"))))), Map.class);
            List<String> texts = r.suggest().get("product").stream()
                    .flatMap(b -> b.completion().options().stream())
                    .map(CompletionSuggestOption::text).toList();
            return new SuggestionList("completion", r.took(), texts);
        } catch (IOException e) {
            throw ApiException.internal("ES_QUERY_FAILED", "completion suggest failed", e);
        }
    }

    public SuggestionList sayt(String q, int size) {
        try {
            SearchResponse<Map> r = es.search(s -> s
                    .index(saytIndex)
                    .size(size)
                    .source(src -> src.filter(f -> f.includes("name")))
                    .query(qq -> qq.multiMatch(m -> m
                            .query(q)
                            .type(TextQueryType.BoolPrefix)
                            .fields("name", "name._2gram", "name._3gram"))), Map.class);
            return new SuggestionList("sayt", r.took(),
                    r.hits().hits().stream().map(h -> (String) ((Map) h.source()).get("name")).toList());
        } catch (IOException e) {
            throw ApiException.internal("ES_QUERY_FAILED", "sayt suggest failed", e);
        }
    }
}
