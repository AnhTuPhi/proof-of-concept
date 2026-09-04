package com.example.espoc.hybrid.controller;

import com.example.espoc.hybrid.service.SearchService;
import com.example.espoc.hybrid.service.SearchService.SearchHit;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService svc;

    public SearchController(SearchService svc) { this.svc = svc; }

    @GetMapping("/lexical")
    public List<SearchHit> lexical(@RequestParam String q, @RequestParam(defaultValue = "10") int k) {
        return svc.lexical(q, k);
    }

    @GetMapping("/knn")
    public List<SearchHit> knn(@RequestParam String q, @RequestParam(defaultValue = "10") int k) {
        return svc.knn(q, k);
    }

    @GetMapping("/hybrid")
    public List<SearchHit> hybrid(@RequestParam String q, @RequestParam(defaultValue = "10") int k) {
        return svc.hybrid(q, k);
    }

    @GetMapping("/compare")
    public Map<String, List<SearchHit>> compare(@RequestParam String q, @RequestParam(defaultValue = "5") int k) {
        Map<String, List<SearchHit>> out = new LinkedHashMap<>();
        out.put("lexical", svc.lexical(q, k));
        out.put("knn",     svc.knn(q, k));
        out.put("hybrid",  svc.hybrid(q, k));
        return out;
    }
}
