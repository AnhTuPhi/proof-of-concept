package com.example.saga.orchestration.activity;

import com.example.saga.orchestration.domain.Payment;
import com.example.saga.orchestration.exception.NonRetryablePaymentException;
import com.example.saga.orchestration.repository.PaymentRepository;
import io.temporal.spring.boot.ActivityImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Activity that books the payment side-effect. Temporal guarantees at-least-once delivery,
 * so the activity is idempotent: a second invocation with the same {@code orderId} returns
 * the previously stored {@code paymentId} instead of double-charging.
 */
@Component
@ActivityImpl(taskQueues = "ORDER_SAGA_TASK_QUEUE")
@RequiredArgsConstructor
@Slf4j
public class PaymentActivitiesImpl implements PaymentActivities {

    private final PaymentRepository paymentRepository;

    @Value("${saga.payment.failure-customer-prefix:deadbeat}")
    private String failureCustomerPrefix;

    @Override
    @Transactional
    public String charge(String orderId, String customerId, BigDecimal amount) {
        // idempotency: if we've already charged this order, return the same payment id
        var existing = paymentRepository.findFirstByOrderIdAndStatus(orderId, Payment.Status.CHARGED);
        if (existing.isPresent()) {
            log.info("Payment for order {} already charged (id {}), returning existing",
                    orderId, existing.get().getPaymentId());
            return existing.get().getPaymentId();
        }

        if (customerId != null && customerId.startsWith(failureCustomerPrefix)) {
            log.warn("Customer {} simulated to fail payment for order {}", customerId, orderId);
            throw new NonRetryablePaymentException("Insufficient funds for customer " + customerId);
        }

        String paymentId = "pay-" + UUID.randomUUID();
        Payment payment = Payment.builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .customerId(customerId)
                .amount(amount)
                .status(Payment.Status.CHARGED)
                .createdAt(Instant.now())
                .build();
        paymentRepository.save(payment);
        log.info("Charged {} for order {} (paymentId {})", amount, orderId, paymentId);
        return paymentId;
    }

    @Override
    @Transactional
    public void refund(String paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null) {
            log.warn("Refund called for unknown paymentId {} — nothing to do", paymentId);
            return;
        }
        if (payment.getStatus() == Payment.Status.REFUNDED) {
            log.info("Payment {} already refunded — idempotent skip", paymentId);
            return;
        }
        payment.setStatus(Payment.Status.REFUNDED);
        paymentRepository.save(payment);
        log.info("Refunded payment {}", paymentId);
    }
}
