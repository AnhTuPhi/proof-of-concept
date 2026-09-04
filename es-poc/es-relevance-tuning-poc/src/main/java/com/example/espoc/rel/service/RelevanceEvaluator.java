package com.example.espoc.rel.service;

import com.example.espoc.common.es.JsonResource;
import com.example.espoc.rel.service.SearchService.Config;
import com.example.espoc.rel.service.SearchService.SearchHit;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Loads judged queries from {@code judgments.json} and computes NDCG@10 and MRR per config.
 *
 * <p>NDCG (Normalized Discounted Cumulative Gain) gives more weight to relevant items at higher
 * positions; MRR (Mean Reciprocal Rank) measures only the first relevant hit. Together they
 * give a reasonable picture of ranking quality.
 */
@Service
public class RelevanceEvaluator {

    public record Judgment(String query, List<String> expected) {}
    public record QueryEval(String query, double ndcg10, double mrr, List<String> topIds) {}
    public record ConfigEval(String config, double avgNdcg10, double avgMrr, List<QueryEval> perQuery) {}
    public record EvalResult(List<Judgment> judgments, List<ConfigEval> configs) {}

    private final SearchService search;
    private final ObjectMapper mapper;
    private List<Judgment> judgments;

    public RelevanceEvaluator(SearchService search, ObjectMapper mapper) {
        this.search = search; this.mapper = mapper;
    }

    public EvalResult run() {
        List<Judgment> js = judgments();
        List<ConfigEval> configs = new ArrayList<>();
        for (Config c : Config.values()) {
            List<QueryEval> per = new ArrayList<>();
            double sumNdcg = 0, sumMrr = 0;
            for (Judgment j : js) {
                List<SearchHit> hits = search.search(j.query(), c, 10);
                List<String> ids = hits.stream().map(SearchHit::id).toList();
                double ndcg = ndcgAt10(ids, new HashSet<>(j.expected()));
                double mrr  = mrr(ids, new HashSet<>(j.expected()));
                per.add(new QueryEval(j.query(), ndcg, mrr, ids));
                sumNdcg += ndcg; sumMrr += mrr;
            }
            configs.add(new ConfigEval(c.name(), sumNdcg / js.size(), sumMrr / js.size(), per));
        }
        return new EvalResult(js, configs);
    }

    public List<Judgment> judgments() {
        if (judgments == null) {
            try (var is = JsonResource.stream("judgments.json")) {
                judgments = mapper.readValue(is, new TypeReference<List<Judgment>>() {});
            } catch (Exception e) {
                throw new RuntimeException("Failed to load judgments.json", e);
            }
        }
        return judgments;
    }

    /* Binary-relevance NDCG@10. Gain = 1 if hit is relevant, 0 otherwise. */
    private double ndcgAt10(List<String> ids, Set<String> relevant) {
        double dcg = 0;
        for (int i = 0; i < Math.min(10, ids.size()); i++) {
            if (relevant.contains(ids.get(i))) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));   // log2(i+2) with 1-indexed positions
            }
        }
        // Ideal DCG: all R relevant in top positions
        double idcg = 0;
        int r = Math.min(10, relevant.size());
        for (int i = 0; i < r; i++) idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        return idcg == 0 ? 0 : dcg / idcg;
    }

    private double mrr(List<String> ids, Set<String> relevant) {
        for (int i = 0; i < ids.size(); i++) {
            if (relevant.contains(ids.get(i))) return 1.0 / (i + 1);
        }
        return 0;
    }
}
