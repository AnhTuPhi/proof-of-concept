package com.claude.kafka.outbox;

import com.claude.kafka.common.event.DomainEvent;
import com.claude.kafka.common.topic.Topics;
import com.claude.kafka.common.util.JsonCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The "right" way to publish events from a service that owns an Oracle DB.
 * <p>
 * Both the {@code orders} insert AND the {@code outbox} insert happen in the
 * same transaction. Either both commit or neither does. There is <strong>no
 * scenario</strong> where the order is persisted but the event is missing,
 * which is exactly what the dual-write problem is.
 * <p>
 * The {@link OutboxPoller} or Debezium ships the outbox row to Kafka later.
 * That step can fail and retry as many times as it needs — the event is
 * durably staged.
 * <p>
 * Why not just publish to Kafka from inside this method? Two failure modes:
 * <ul>
 *   <li>Publish succeeds, DB commit fails → ghost event for an order that
 *       never existed.</li>
 *   <li>DB commit succeeds, publish fails (broker down, network blip) →
 *       silently lost event.</li>
 * </ul>
 * Both are common in production. The outbox eliminates both.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRED)
    public String placeOrder(String customerId, BigDecimal amount) {
        String orderId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        jdbc.update("INSERT INTO appuser.orders " +
                        "(order_id, customer_id, status, total_amount, created_at, updated_at) " +
                        "VALUES (?, ?, 'PLACED', ?, ?, ?)",
                orderId, customerId, amount,
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        DomainEvent<Map<String, Object>> event = DomainEvent.of(
                "OrderPlaced", "Order", orderId,
                Map.of("customerId", customerId,
                        "amount", amount,
                        "currency", "USD"));

        jdbc.update("INSERT INTO appuser.outbox " +
                        "(id, aggregate_type, aggregate_id, event_type, payload, partition_key, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                event.getEventId(),                     // outbox row id == event id (idempotent publish)
                "Order",
                orderId,
                "OrderPlaced",
                JsonCodec.toJson(event),
                orderId,                                // partition key keeps order events ordered
                java.sql.Timestamp.from(now));

        log.info("Order {} persisted with outbox event {}", orderId, event.getEventId());
        return orderId;
    }

    /**
     * Demonstrates that even when the SECOND statement fails AFTER the first
     * one succeeded, the whole transaction rolls back and no event leaks.
     * Toggle from the controller with {@code ?fail=true}.
     */
    @Transactional
    public void placeOrderAndFail(String customerId, BigDecimal amount) {
        placeOrder(customerId, amount);
        throw new RuntimeException("Simulated post-insert failure");
    }
}
