package com.claude.kafka.saga;

import com.claude.kafka.common.event.DomainEvent;
import com.claude.kafka.common.topic.Topics;
import com.claude.kafka.common.util.JsonCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kick off a saga via:
 * <pre>
 *   POST /saga/orders?sku=SKU-001&qty=2&customerId=cust-1
 *   POST /saga/failure-rate?rate=0.5
 *   GET  /saga/inventory
 * </pre>
 */
@RestController
@RequestMapping("/saga")
@RequiredArgsConstructor
public class SagaController {

    private final KafkaTemplate<String, String> kafka;
    private final PaymentService payments;
    private final JdbcTemplate jdbc;

    @PostMapping("/orders")
    public Map<String, Object> placeOrder(@RequestParam(defaultValue = "cust-1") String customerId,
                                          @RequestParam(defaultValue = "SKU-001") String sku,
                                          @RequestParam(defaultValue = "1") int qty) {
        String orderId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO appuser.orders " +
                "(order_id, customer_id, status, total_amount) VALUES (?, ?, 'PLACED', ?)",
                orderId, customerId, BigDecimal.valueOf(qty * 100L));

        DomainEvent<?> e = DomainEvent.of("OrderPlaced", "Order", orderId,
                Map.of("customerId", customerId, "sku", sku, "qty", qty));
        kafka.send(Topics.ORDERS_PLACED, orderId, JsonCodec.toJson(e));
        return Map.of("orderId", orderId, "sku", sku, "qty", qty);
    }

    @PostMapping("/failure-rate")
    public Map<String, Object> setFailureRate(@RequestParam double rate) {
        payments.setFailureRate(rate);
        return Map.of("paymentFailureRate", rate);
    }

    @GetMapping("/inventory")
    public List<Map<String, Object>> inventory() {
        return jdbc.queryForList("SELECT sku, available, reserved FROM appuser.inventory");
    }

    @GetMapping("/orders/{id}")
    public Map<String, Object> getOrder(@PathVariable("id") String id) {
        return jdbc.queryForMap("SELECT order_id, customer_id, status, total_amount FROM " +
                "appuser.orders WHERE order_id = ?", id);
    }
}
