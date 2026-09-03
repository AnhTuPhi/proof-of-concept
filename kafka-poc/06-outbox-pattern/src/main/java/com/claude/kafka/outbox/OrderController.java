package com.claude.kafka.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService service;
    private final OutboxPoller poller;
    private final JdbcTemplate jdbc;

    @PostMapping
    public Map<String, Object> create(@RequestParam(defaultValue = "cust-1") String customerId,
                                      @RequestParam(defaultValue = "99.99") BigDecimal amount,
                                      @RequestParam(defaultValue = "false") boolean fail) {
        if (fail) {
            try {
                service.placeOrderAndFail(customerId, amount);
            } catch (RuntimeException e) {
                return Map.of("status", "rolled-back", "error", e.getMessage());
            }
        }
        String id = service.placeOrder(customerId, amount);
        return Map.of("orderId", id);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Long unpublished = jdbc.queryForObject(
                "SELECT COUNT(*) FROM appuser.outbox WHERE published_at IS NULL",
                Long.class);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM appuser.outbox", Long.class);
        return Map.of(
                "unpublished", unpublished,
                "totalOutbox", total,
                "publishedThisInstance", poller.getPublishedTotal()
        );
    }
}
