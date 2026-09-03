package com.example.billing.dunning;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Fake payment gateway. Success/failure is driven by the PaymentMethod state so tests
 * can deterministically reproduce dunning flows. In production this would call Stripe / Adyen.
 */
@Component
public class ChargeGateway {

    public ChargeOutcome charge(PaymentMethod method, BigDecimal amount, String currency, LocalDate today) {
        if (method == null) {
            return ChargeOutcome.failed("missing_payment_method");
        }
        if (!method.canCharge(today)) {
            return ChargeOutcome.failed(method.declineReason(today));
        }
        return ChargeOutcome.succeeded("ch_" + System.nanoTime());
    }

    public record ChargeOutcome(boolean success, String reference, String reason) {
        public static ChargeOutcome succeeded(String reference) {
            return new ChargeOutcome(true, reference, null);
        }

        public static ChargeOutcome failed(String reason) {
            return new ChargeOutcome(false, null, reason);
        }
    }
}
