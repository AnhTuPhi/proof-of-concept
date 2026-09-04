package com.example.espoc.ac.controller;

import com.example.espoc.ac.service.SuggestService;
import com.example.espoc.ac.service.SuggestService.SuggestionList;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/suggest")
public class SuggestController {

    private final SuggestService svc;

    public SuggestController(SuggestService svc) { this.svc = svc; }

    @GetMapping("/ngram")
    public SuggestionList ngram(@RequestParam String q, @RequestParam(defaultValue = "10") int size) {
        return svc.ngram(q, size);
    }

    @GetMapping("/completion")
    public SuggestionList completion(@RequestParam String q, @RequestParam(defaultValue = "10") int size) {
        return svc.completion(q, size);
    }

    @GetMapping("/sayt")
    public SuggestionList sayt(@RequestParam String q, @RequestParam(defaultValue = "10") int size) {
        return svc.sayt(q, size);
    }

    @GetMapping("/compare")
    public Map<String, Object> compare(@RequestParam String q, @RequestParam(defaultValue = "5") int size) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ngram",      svc.ngram(q, size));
        out.put("completion", svc.completion(q, size));
        out.put("sayt",       svc.sayt(q, size));
        return out;
    }
}
