package com.claude.kafka.saga;

import com.claude.kafka.common.event.DomainEvent;
import com.claude.kafka.common.topic.Topics;
import com.claude.kafka.common.util.JsonCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Saga step: reserve inventory.
 * <p>
 * Listens for {@code orders.placed.v1}, atomically updates the inventory row
 * (with optimistic concurrency via the WHERE clause), and emits either
 * {@code inventory.reserved.v1} or {@code inventory.reserve.failed.v1}.
 * <p>
 * Note the listener handler is small on purpose: it's just translation. The
 * actual business invariant (don't oversell) lives in the SQL update count.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;

    @KafkaListener(topics = Topics.ORDERS_PLACED, groupId = "saga-inventory")
    @Transactional
    public void onOrderPlaced(String json) {
        DomainEvent<?> in = JsonCodec.fromJson(json, DomainEvent.class);
        Map<?,?> payload = (Map<?,?>) in.getPayload();
        String sku = (String) payload.getOrDefault("sku", "SKU-001");
        int qty   = ((Number) payload.getOrDefault("qty", 1)).intValue();

        int updated = jdbc.update(
                "UPDATE appuser.inventory SET available = available - ?, " +
                        "reserved = reserved + ?, updated_at = SYSTIMESTAMP " +
                        "WHERE sku = ? AND available >= ?",
                qty, qty, sku, qty);

        if (updated == 1) {
            log.info("Reserved {} of {} for order {}", qty, sku, in.getAggregateId());
            kafka.send(Topics.INVENTORY_RESERVED, in.getAggregateId(),
                    JsonCodec.toJson(DomainEvent.of(
                            "InventoryReserved", "Order", in.getAggregateId(),
                            Map.of("sku", sku, "qty", qty))));
        } else {
            log.warn("Insufficient inventory for {}", sku);
            kafka.send(Topics.INVENTORY_RESERVE_FAILED, in.getAggregateId(),
                    JsonCodec.toJson(DomainEvent.of(
                            "InventoryReserveFailed", "Order", in.getAggregateId(),
                            Map.of("sku", sku, "reason", "OUT_OF_STOCK"))));
        }
    }

    /**
     * Compensating action: triggered by {@code payments.failed.v1}.
     * Releases the previously reserved stock so the saga ends in a consistent
     * state.
     */
    @KafkaListener(topics = Topics.PAYMENTS_FAILED, groupId = "saga-inventory-compensation")
    @Transactional
    public void onPaymentFailed(String json) {
        DomainEvent<?> in = JsonCodec.fromJson(json, DomainEvent.class);
        Map<?,?> payload = (Map<?,?>) in.getPayload();
        String sku = (String) payload.getOrDefault("sku", "SKU-001");
        int qty   = ((Number) payload.getOrDefault("qty", 1)).intValue();

        jdbc.update("UPDATE appuser.inventory SET available = available + ?, " +
                        "reserved = reserved - ?, updated_at = SYSTIMESTAMP WHERE sku = ?",
                qty, qty, sku);
        log.info("COMPENSATION: released {} of {} for order {}", qty, sku, in.getAggregateId());
    }
}
