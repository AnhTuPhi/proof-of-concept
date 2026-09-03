package com.example.billing.proration;

import com.example.billing.common.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proration through the service layer. The billing cycle is created by
 * {@code today.plusMonths(1)}, so Jan 1 → Feb 1 is a 31-day cycle. Tests are written
 * against the actual day-count math, not idealised 30-day approximations.
 */
@SpringBootTest
@Transactional
class SubscriptionServiceTest {

    @Autowired
    SubscriptionService service;

    @Test
    void newSubscriptionIssuesFullPriceInvoice() {
        Subscription sub = service.create("cust-1", "basic", LocalDate.of(2026, 1, 1));
        Invoice invoice = service.invoicesFor(sub.getId()).get(0);

        assertEquals(new BigDecimal("10.00"), invoice.total().amount());
        assertEquals(1, invoice.getLineItems().size());
    }

    @Test
    void upgradeMidCycleProducesProratedInvoice() {
        // Cycle: Jan 1 → Feb 1 (31 days). Change on Jan 16: 16 days remaining.
        // Credit basic: -10 × 16/31 = -5.16. Charge pro: 30 × 16/31 = 15.48. Net = 10.32.
        Subscription sub = service.create("cust-2", "basic", LocalDate.of(2026, 1, 1));
        Invoice invoice = service.changePlan(sub.getId(), "pro",
                ChangeStrategy.IMMEDIATE_PRORATE, LocalDate.of(2026, 1, 16));

        assertEquals(new BigDecimal("10.32"), invoice.total().amount());
        assertTrue(invoice.getLineItems().size() >= 2);
    }

    @Test
    void downgradeMidCycleBanksCreditNoCashRefund() {
        // Credit pro: -15.48. Charge basic: 5.16. Net = -10.32 → banked as credit, invoice settles to $0.
        Subscription sub = service.create("cust-3", "pro", LocalDate.of(2026, 1, 1));
        Invoice invoice = service.changePlan(sub.getId(), "basic",
                ChangeStrategy.IMMEDIATE_PRORATE, LocalDate.of(2026, 1, 16));

        assertEquals(new BigDecimal("0.00"), invoice.total().amount());

        Money balance = service.creditBalance("cust-3");
        assertEquals(new BigDecimal("10.32"), balance.amount());
    }

    @Test
    void endOfCycleStrategyDoesNotChargeNow() {
        Subscription sub = service.create("cust-4", "basic", LocalDate.of(2026, 1, 1));
        Invoice invoice = service.changePlan(sub.getId(), "pro",
                ChangeStrategy.END_OF_CYCLE, LocalDate.of(2026, 1, 16));

        assertEquals(new BigDecimal("0.00"), invoice.total().amount());

        List<Invoice> invoices = service.invoicesFor(sub.getId());
        assertEquals(2, invoices.size()); // initial + scheduling marker
    }

    @Test
    void cancelMidCycleAddsUnusedCreditAndCancelsSub() {
        // Cancel on Jan 21: 11 days remaining of 31 on $30 plan → $10.65 credit.
        Subscription sub = service.create("cust-5", "pro", LocalDate.of(2026, 1, 1));
        Invoice cancelInvoice = service.cancel(sub.getId(), LocalDate.of(2026, 1, 21));

        Money balance = service.creditBalance("cust-5");
        assertEquals(new BigDecimal("10.65"), balance.amount());
        assertEquals(new BigDecimal("0.00"), cancelInvoice.total().amount());
    }

    @Test
    void existingCreditOffsetsNextInvoice() {
        // Step 1: downgrade pro → basic on Jan 16 → $10.32 credit banked.
        Subscription sub = service.create("cust-6", "pro", LocalDate.of(2026, 1, 1));
        service.changePlan(sub.getId(), "basic", ChangeStrategy.IMMEDIATE_PRORATE,
                LocalDate.of(2026, 1, 16));
        assertEquals(new BigDecimal("10.32"), service.creditBalance("cust-6").amount());

        // After downgrade the cycle is reset to Jan 16 → Feb 1 (16 days) on basic.
        // Step 2: upgrade basic → pro on Jan 21. 11 days remaining of 16.
        //   Credit basic: -10 × 11/16 = -6.88
        //   Charge pro:    30 × 11/16 = 20.63
        //   Net: 13.75 → consume $10.32 credit → invoice $3.43, credit $0.
        Invoice up = service.changePlan(sub.getId(), "pro", ChangeStrategy.IMMEDIATE_PRORATE,
                LocalDate.of(2026, 1, 21));

        assertEquals(new BigDecimal("3.43"), up.total().amount());
        assertEquals(new BigDecimal("0.00"), service.creditBalance("cust-6").amount());
    }
}
