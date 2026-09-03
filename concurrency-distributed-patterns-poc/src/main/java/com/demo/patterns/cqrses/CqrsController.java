package com.demo.patterns.cqrses;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/demo/cqrses")
public class CqrsController {

    private final AccountCommandService commands;
    private final AccountBalanceViewRepository views;

    public CqrsController(AccountCommandService commands,
                          AccountBalanceViewRepository views) {
        this.commands = commands;
        this.views = views;
    }

    @PostMapping("/accounts")
    public Map<String, Object> open(@RequestParam(defaultValue = "0") long initialDeposit) {
        String id = commands.openAccount(initialDeposit);
        return Map.of("aggregateId", id, "initialDeposit", initialDeposit);
    }

    @PostMapping("/accounts/{id}/deposit")
    public Map<String, Object> deposit(@PathVariable String id, @RequestParam long amount) {
        commands.deposit(id, amount);
        AccountAggregate a = commands.load(id);
        return Map.of("aggregateId", id, "balance", a.balance(), "version", a.version(),
                "note", "balance from REPLAYED events (write side)");
    }

    @PostMapping("/accounts/{id}/withdraw")
    public ResponseEntity<Map<String, Object>> withdraw(@PathVariable String id, @RequestParam long amount) {
        try {
            commands.withdraw(id, amount);
            AccountAggregate a = commands.load(id);
            return ResponseEntity.ok(Map.of("aggregateId", id, "balance", a.balance(),
                    "version", a.version(), "note", "balance from REPLAYED events"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /** Reads from the projection — eventually consistent with the write side. */
    @GetMapping("/accounts/{id}/view")
    public ResponseEntity<Map<String, Object>> view(@PathVariable String id) {
        return views.findById(id)
                .map(v -> ResponseEntity.ok(Map.<String, Object>of(
                        "aggregateId", v.getAggregateId(),
                        "balance", v.getBalance(),
                        "lastEventVersion", v.getLastEventVersion(),
                        "updatedAt", v.getUpdatedAt(),
                        "note", "from READ MODEL (projection)"
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Replays the full event stream — proves we can reconstruct state from history. */
    @GetMapping("/accounts/{id}/events")
    public List<Map<String, Object>> events(@PathVariable String id) {
        return commands.history(id).stream().map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", e.getId());
            m.put("version", e.getVersion());
            m.put("type", e.getType());
            m.put("payload", e.getPayload());
            m.put("occurredAt", e.getOccurredAt());
            return m;
        }).toList();
    }
}
