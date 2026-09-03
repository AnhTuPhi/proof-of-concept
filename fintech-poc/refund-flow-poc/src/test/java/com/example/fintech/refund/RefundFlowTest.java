package com.example.fintech.refund;

import com.example.fintech.common.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RefundFlowTest {

    @Autowired RefundService service;
    @Autowired OriginalPaymentRepository payments;
    @Autowired RefundRepository refunds;

    @Test
    void partialRefund_thenAnotherPartial_thenRemainder() {
        OriginalPayment p = givenPayment(new BigDecimal("500.00"), BigDecimal.ZERO,
                OriginalPayment.PaymentMethodStatus.ACTIVE, Instant.now().minus(2, ChronoUnit.DAYS));

        Refund r1 = service.refund(key(), new RefundRequest(p.getId(), new BigDecimal("100.00"), false));
        Refund r2 = service.refund(key(), new RefundRequest(p.getId(), new BigDecimal("150.00"), false));
        Refund r3 = service.refund(key(), new RefundRequest(p.getId(), null, true));

        assertEquals(new BigDecimal("100.00"), r1.getAmount());
        assertEquals(new BigDecimal("150.00"), r2.getAmount());
        assertEquals(new BigDecimal("250.00"), r3.getAmount(), "full refund must consume the remaining balance");

        OriginalPayment after = payments.findById(p.getId()).orElseThrow();
        assertEquals(0, after.remainingRefundable().signum(), "no more refundable amount left");
    }

    @Test
    void refundAfterCoupon_refundsActualPaidAmount_notFaceValue() {
        OriginalPayment p = givenPayment(new BigDecimal("500.00"), new BigDecimal("100.00"),
                OriginalPayment.PaymentMethodStatus.ACTIVE, Instant.now().minus(1, ChronoUnit.DAYS));

        Refund full = service.refund(key(), new RefundRequest(p.getId(), null, true));

        assertEquals(new BigDecimal("400.00"), full.getAmount(),
                "full refund after ₫100 coupon discount must equal ₫400, not ₫500 face value");
    }

    @Test
    void expiredCard_fallsBackToBankTransfer() {
        OriginalPayment p = givenPayment(new BigDecimal("200.00"), BigDecimal.ZERO,
                OriginalPayment.PaymentMethodStatus.EXPIRED, Instant.now().minus(30, ChronoUnit.DAYS));

        Refund r = service.refund(key(), new RefundRequest(p.getId(), null, true));

        assertEquals(Refund.RefundChannel.BANK_TRANSFER, r.getChannel());
        assertEquals(Refund.RefundStatus.SETTLED, r.getStatus());
    }

    @Test
    void removedPaymentMethod_fallsBackToStoreCredit() {
        OriginalPayment p = givenPayment(new BigDecimal("75.00"), BigDecimal.ZERO,
                OriginalPayment.PaymentMethodStatus.REMOVED, Instant.now().minus(60, ChronoUnit.DAYS));

        Refund r = service.refund(key(), new RefundRequest(p.getId(), null, true));

        assertEquals(Refund.RefundChannel.STORE_CREDIT, r.getChannel());
    }

    @Test
    void refundWindowExpired_throws() {
        OriginalPayment p = givenPayment(new BigDecimal("50.00"), BigDecimal.ZERO,
                OriginalPayment.PaymentMethodStatus.ACTIVE, Instant.now().minus(200, ChronoUnit.DAYS));

        assertThrows(IllegalStateException.class, () ->
                service.refund(key(), new RefundRequest(p.getId(), null, true)));
    }

    @Test
    void sameIdempotencyKey_returnsSameRefund_noDoubleRefund() {
        OriginalPayment p = givenPayment(new BigDecimal("100.00"), BigDecimal.ZERO,
                OriginalPayment.PaymentMethodStatus.ACTIVE, Instant.now().minus(1, ChronoUnit.DAYS));
        String key = key();

        Refund first = service.refund(key, new RefundRequest(p.getId(), new BigDecimal("30.00"), false));
        Refund second = service.refund(key, new RefundRequest(p.getId(), new BigDecimal("30.00"), false));

        assertEquals(first.getId(), second.getId(), "same idempotency key → same refund id");
        assertEquals(1, refunds.findByPaymentId(p.getId()).size(), "exactly 1 refund row");
        assertEquals(new BigDecimal("30.00"), payments.findById(p.getId()).orElseThrow().getRefundedAmount(),
                "refunded amount must reflect exactly one ₫30 refund");
    }

    @Test
    void overRefund_throws() {
        OriginalPayment p = givenPayment(new BigDecimal("100.00"), BigDecimal.ZERO,
                OriginalPayment.PaymentMethodStatus.ACTIVE, Instant.now().minus(1, ChronoUnit.DAYS));
        service.refund(key(), new RefundRequest(p.getId(), new BigDecimal("80.00"), false));

        assertThrows(IllegalStateException.class, () ->
                service.refund(key(), new RefundRequest(p.getId(), new BigDecimal("50.00"), false)));
    }

    private OriginalPayment givenPayment(BigDecimal gross, BigDecimal couponDiscount,
                                         OriginalPayment.PaymentMethodStatus methodStatus, Instant capturedAt) {
        OriginalPayment p = new OriginalPayment(
                "pay-" + UUID.randomUUID(), gross, couponDiscount, Currency.USD, capturedAt, methodStatus);
        return payments.save(p);
    }

    private String key() { return "key-" + UUID.randomUUID(); }
}
