package com.example.espoc.rel.controller;

import com.example.espoc.rel.service.RelevanceEvaluator;
import com.example.espoc.rel.service.SearchService;
import com.example.espoc.rel.service.SearchService.Config;
import com.example.espoc.rel.service.SearchService.SearchHit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class SearchController {

    private final SearchService search;
    private final RelevanceEvaluator evaluator;

    public SearchController(SearchService search, RelevanceEvaluator evaluator) {
        this.search = search;
        this.evaluator = evaluator;
    }

    @GetMapping("/search")
    public List<SearchHit> search(@RequestParam String q,
                                  @RequestParam(defaultValue = "tuned") Config config) {
        return search.search(q, config, 10);
    }

    @GetMapping("/eval/run")
    public RelevanceEvaluator.EvalResult evalRun() { return evaluator.run(); }

    @GetMapping("/eval/queries")
    public List<RelevanceEvaluator.Judgment> queries() { return evaluator.judgments(); }
}
