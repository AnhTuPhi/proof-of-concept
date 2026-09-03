package com.demo.patterns.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class IndexController {

    @GetMapping("/")
    public Map<String, Object> index() {
        return Map.of(
                "app", "concurrency-distributed-patterns-demo",
                "patterns", List.of(
                        Map.of("name", "Distributed Lock (Redlock)", "base", "/demo/redlock"),
                        Map.of("name", "Optimistic Lock (@Version)",  "base", "/demo/optimistic"),
                        Map.of("name", "Transactional Outbox",        "base", "/demo/outbox"),
                        Map.of("name", "Saga (orchestration)",        "base", "/demo/saga"),
                        Map.of("name", "CQRS + Event Sourcing",       "base", "/demo/cqrses")
                ),
                "h2Console", "/h2",
                "actuator", "/actuator/health"
        );
    }
}
