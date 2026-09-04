package com.example.saga.choreography.payment.service;

import com.example.saga.choreography.payment.domain.Payment;
import com.example.saga.choreography.payment.domain.ProcessedEvent;
import com.example.saga.choreography.payment.messaging.SagaEventPublisher;
import com.example.saga.choreography.payment.repository.PaymentRepository;
import com.example.saga.choreography.payment.repository.ProcessedEventRepository;
import com.example.saga.common.enums.FailureReason;
import com.example.saga.common.events.InventoryFailed;
import com.example.saga.common.events.OrderCreated;
import com.example.saga.common.events.PaymentCompleted;
import com.example.saga.common.events.PaymentFailed;
import com.example.saga.common.events.PaymentRefunded;
import com.example.saga.common.events.SagaEvent;
import com.example.saga.common.events.ShippingFailed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Payment-side participant of the choreography saga.
 *
 * <p>Forward path: on {@link OrderCreated} we attempt to charge and emit
 * {@link PaymentCompleted} or {@link PaymentFailed}. The simulated gateway fails
 * deterministically for customer IDs starting with a configured prefix, so test
 * scenarios can opt into the failure path.
 *
 * <p>Compensation path: on {@link InventoryFailed} or {@link ShippingFailed} we
 * reverse the charge and emit {@link PaymentRefunded}, which the order service
 * uses to finalize the saga as compensated.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final SagaEventPublisher publisher;

    @Value("${payment.simulated-failure-customer-prefix:deadbeat}")
    private String failureCustomerPrefix;

    @Transactional
    public void handle(SagaEvent event) {
        MDC.put("sagaId", event.sagaId());
        try {
            if (processedEventRepository.existsById(event.eventId())) {
                log.debug("Skipping duplicate event {}", event.eventId());
                return;
            }

            switch (event) {
                case OrderCreated e     -> processPayment(e);
                case InventoryFailed e  -> refundForInventoryFailure(e);
                case ShippingFailed e   -> refundForShippingFailure(e);
                default -> { /* payment service does not react to other events */ }
            }

            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(event.eventId())
                    .sagaId(event.sagaId())
                    .eventType(event.getClass().getSimpleName())
                    .processedAt(Instant.now())
                    .build());
        } finally {
            MDC.remove("sagaId");
        }
    }

    private void processPayment(OrderCreated event) {
        if (paymentRepository.findBySagaId(event.sagaId()).isPresent()) {
            log.warn("Payment for saga {} already processed — skipping", event.sagaId());
            return;
        }

        boolean willFail = event.customerId() != null && event.customerId().startsWith(failureCustomerPrefix);

        if (willFail) {
            Payment failed = Payment.builder()
                    .paymentId("pay-" + UUID.randomUUID())
                    .sagaId(event.sagaId())
                    .orderId(event.orderId())
                    .customerId(event.customerId())
                    .amount(event.totalAmount())
                    .status(Payment.Status.FAILED)
                    .failureReason("Insufficient funds (simulated)")
                    .createdAt(Instant.now())
                    .build();
            paymentRepository.save(failed);

            publisher.publish(new PaymentFailed(
                    UUID.randomUUID().toString(),
                    event.sagaId(),
                    event.orderId(),
                    event.customerId(),
                    event.totalAmount(),
                    FailureReason.INSUFFICIENT_FUNDS,
                    "Customer " + event.customerId() + " could not be charged",
                    Instant.now()));
            return;
        }

        Payment charged = Payment.builder()
                .paymentId("pay-" + UUID.randomUUID())
                .sagaId(event.sagaId())
                .orderId(event.orderId())
                .customerId(event.customerId())
                .amount(event.totalAmount())
                .status(Payment.Status.CHARGED)
                .createdAt(Instant.now())
                .build();
        paymentRepository.save(charged);

        publisher.publish(new PaymentCompleted(
                UUID.randomUUID().toString(),
                event.sagaId(),
                event.orderId(),
                charged.getPaymentId(),
                event.customerId(),
                event.totalAmount(),
                Instant.now()));
    }

    private void refundForInventoryFailure(InventoryFailed event) {
        refundPayment(event.sagaId(), event.orderId());
    }

    private void refundForShippingFailure(ShippingFailed event) {
        refundPayment(event.sagaId(), event.orderId());
    }

    private void refundPayment(String sagaId, String orderId) {
        Payment payment = paymentRepository.findBySagaId(sagaId).orElse(null);
        if (payment == null || payment.getStatus() != Payment.Status.CHARGED) {
            log.info("Skip refund for saga {} — no charged payment found", sagaId);
            return;
        }
        payment.setStatus(Payment.Status.REFUNDED);
        paymentRepository.save(payment);

        BigDecimal amount = payment.getAmount();
        publisher.publish(new PaymentRefunded(
                UUID.randomUUID().toString(),
                sagaId,
                orderId,
                payment.getPaymentId(),
                payment.getCustomerId(),
                amount,
                Instant.now()));
        log.info("Refunded {} for saga {}", amount, sagaId);
    }
}
