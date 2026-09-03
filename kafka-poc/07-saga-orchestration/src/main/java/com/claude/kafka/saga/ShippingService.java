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

import java.util.Map;

/**
 * Saga step: trigger shipping after payment is captured, and mark the order
 * COMPLETED in the local DB. This is the final positive branch of the saga.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShippingService {

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;

    @KafkaListener(topics = Topics.PAYMENTS_COMPLETED, groupId = "saga-shipping")
    public void onPaymentCompleted(String json) {
        DomainEvent<?> in = JsonCodec.fromJson(json, DomainEvent.class);
        String orderId = in.getAggregateId();

        jdbc.update("UPDATE appuser.orders SET status = 'COMPLETED', updated_at = SYSTIMESTAMP " +
                "WHERE order_id = ?", orderId);

        kafka.send(Topics.SHIPPING_COMPLETED, orderId,
                JsonCodec.toJson(DomainEvent.of(
                        "ShippingScheduled", "Order", orderId,
                        Map.of("carrier", "ACME"))));
        log.info("Order {} completed end-to-end", orderId);
    }

    /**
     * Compensating action on the order itself: mark CANCELLED so reporting,
     * notifications, etc. see the final state.
     */
    @KafkaListener(topics = Topics.PAYMENTS_FAILED, groupId = "saga-order-cancellation")
    public void onPaymentFailed(String json) {
        DomainEvent<?> in = JsonCodec.fromJson(json, DomainEvent.class);
        jdbc.update("UPDATE appuser.orders SET status = 'CANCELLED', updated_at = SYSTIMESTAMP " +
                "WHERE order_id = ?", in.getAggregateId());
        log.info("COMPENSATION: order {} cancelled due to payment failure", in.getAggregateId());
    }
}
