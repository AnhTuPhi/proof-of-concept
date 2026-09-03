package com.demo.resilience.controller;

import com.demo.resilience.service.BulkheadService;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequestMapping("/bulkhead")
public class BulkheadController {

    private final BulkheadService service;
    private final BulkheadRegistry registry;

    public BulkheadController(BulkheadService service, BulkheadRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    @GetMapping("/a")
    public String a(@RequestParam(defaultValue = "200") long workMillis) {
        return service.callA(workMillis);
    }

    @GetMapping("/b")
    public String b(@RequestParam(defaultValue = "200") long workMillis) {
        return service.callB(workMillis);
    }

    /**
     * Saturate service A with a burst — half should land, half should be
     * rejected fast by the bulkhead. Then verify that B is still healthy
     * via /bulkhead/b.
     */
    @GetMapping("/saturate-a")
    public Map<String, Object> saturateA(@RequestParam(defaultValue = "20") int concurrency,
                                         @RequestParam(defaultValue = "800") long workMillis) {
        List<String> results = new CopyOnWriteArrayList<>();
        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            threads.add(Thread.ofVirtual().start(() -> results.add(service.callA(workMillis))));
        }
        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        long accepted = results.stream().filter(r -> r.startsWith("A:")).count();
        long rejected = results.size() - accepted;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", accepted);
        out.put("rejected", rejected);
        out.put("sample", results.stream().limit(5).toList());
        return out;
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String name : List.of("serviceA", "serviceB")) {
            Bulkhead b = registry.bulkhead(name);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("availableConcurrentCalls", b.getMetrics().getAvailableConcurrentCalls());
            info.put("maxAllowedConcurrentCalls", b.getMetrics().getMaxAllowedConcurrentCalls());
            out.put(name, info);
        }
        return out;
    }
}
