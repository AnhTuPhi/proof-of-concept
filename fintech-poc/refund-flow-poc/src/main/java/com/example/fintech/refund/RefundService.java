package com.example.fintech.refund;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@Service
public class RefundService {

    private static final Duration REFUND_WINDOW = Duration.ofDays(180);

    private final OriginalPaymentRepository payments;
    private final RefundRepository refunds;

    public RefundService(OriginalPaymentRepository payments, RefundRepository refunds) {
        this.payments = payments;
        this.refunds = refunds;
    }

    @Transactional
    public Refund refund(String idempotencyKey, RefundRequest request) {
        return refunds.findByIdempotencyKey(idempotencyKey).orElseGet(() -> createRefund(idempotencyKey, request));
    }

    private Refund createRefund(String idempotencyKey, RefundRequest request) {
        OriginalPayment payment = payments.findById(request.paymentId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment " + request.paymentId()));

        if (Duration.between(payment.getCapturedAt(), Instant.now()).compareTo(REFUND_WINDOW) > 0) {
            throw new IllegalStateException("Refund window expired (180 days)");
        }

        BigDecimal amount = computeRefundAmount(payment, request);
        Refund.RefundChannel channel = pickChannel(payment);

        Refund refund = new Refund(payment.getId(), idempotencyKey, amount, channel);
        try {
            refunds.saveAndFlush(refund);
        } catch (DataIntegrityViolationException race) {
            return refunds.findByIdempotencyKey(idempotencyKey).orElseThrow();
        }

        refund.transitionTo(Refund.RefundStatus.PROCESSING);
        refund.transitionTo(Refund.RefundStatus.SETTLED);
        payment.recordRefund(amount);
        payments.save(payment);
        return refunds.save(refund);
    }

    private BigDecimal computeRefundAmount(OriginalPayment payment, RefundRequest request) {
        if (request.fullRefund()) {
            return payment.remainingRefundable();
        }
        BigDecimal requested = request.requestedAmount();
        if (requested == null || requested.signum() <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive");
        }
        if (requested.compareTo(payment.remainingRefundable()) > 0) {
            throw new IllegalStateException(
                    "Requested " + requested + " exceeds remaining refundable " + payment.remainingRefundable());
        }
        return requested;
    }

    private Refund.RefundChannel pickChannel(OriginalPayment payment) {
        return switch (payment.getMethodStatus()) {
            case ACTIVE -> Refund.RefundChannel.ORIGINAL_CARD;
            case EXPIRED -> Refund.RefundChannel.BANK_TRANSFER;
            case REMOVED -> Refund.RefundChannel.STORE_CREDIT;
        };
    }
}
