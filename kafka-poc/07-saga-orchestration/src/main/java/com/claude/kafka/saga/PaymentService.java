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

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Saga step: charge the payment method after inventory is reserved.
 * <p>
 * For demo purposes, payments succeed unless {@code ?failRate=0.5} is set in
 * the request (a process-wide flag toggled from the controller). The point is
 * to exercise the compensation path back into the inventory service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;

    private volatile double failureRate = 0.0;

    public void setFailureRate(double rate) { this.failureRate = rate; }

    @KafkaListener(topics = Topics.INVENTORY_RESERVED, groupId = "saga-payment")
    @Transactional
    public void onInventoryReserved(String json) {
        DomainEvent<?> in = JsonCodec.fromJson(json, DomainEvent.class);
        String orderId = in.getAggregateId();

        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            kafka.send(Topics.PAYMENTS_FAILED, orderId,
                    JsonCodec.toJson(DomainEvent.of(
                            "PaymentFailed", "Order", orderId,
                            Map.of("reason", "INSUFFICIENT_FUNDS",
                                    "sku", ((Map<?,?>)in.getPayload()).getOrDefault("sku","SKU-001"),
                                    "qty", ((Map<?,?>)in.getPayload()).getOrDefault("qty",1)))));
            log.warn("Payment failed for order {}", orderId);
            return;
        }

        BigDecimal amount = new BigDecimal("100.00");
        String paymentId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO appuser.payments (payment_id, order_id, amount, status) " +
                "VALUES (?, ?, ?, 'COMPLETED')", paymentId, orderId, amount);

        kafka.send(Topics.PAYMENTS_COMPLETED, orderId,
                JsonCodec.toJson(DomainEvent.of(
                        "PaymentCompleted", "Order", orderId,
                        Map.of("paymentId", paymentId, "amount", amount))));
        log.info("Payment completed for order {} (paymentId={})", orderId, paymentId);
    }
}
