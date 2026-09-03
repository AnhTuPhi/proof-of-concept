package com.example.billing.proration;

import com.example.billing.common.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProrationCalculatorTest {

    private final ProrationCalculator calculator = new ProrationCalculator();
    private final Plan basic = new Plan("basic", "Basic", Money.usd("10.00"));
    private final Plan pro = new Plan("pro", "Pro", Money.usd("30.00"));

    @Test
    void upgrade_midCycle_30days_dayFifteen_givesHalfCreditAndHalfCharge() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);
        LocalDate change = LocalDate.of(2026, 1, 16); // day 16: 15 days remaining

        List<InvoiceLineItem> items = calculator.prorate(basic, pro, start, end, change);

        assertEquals(2, items.size());
        InvoiceLineItem credit = items.get(0);
        InvoiceLineItem charge = items.get(1);

        assertEquals(InvoiceLineItem.LineItemType.CREDIT, credit.getType());
        assertEquals(InvoiceLineItem.LineItemType.CHARGE, charge.getType());
        assertEquals(new BigDecimal("-5.00"), credit.getAmount());
        assertEquals(new BigDecimal("15.00"), charge.getAmount());

        BigDecimal net = credit.getAmount().add(charge.getAmount());
        assertEquals(new BigDecimal("10.00"), net,
                "Net = unused new minus unused old should equal $10 (upgrade from $10 to $30, half cycle)");
    }

    @Test
    void downgrade_midCycle_givesNegativeNet() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);
        LocalDate change = LocalDate.of(2026, 1, 16);

        List<InvoiceLineItem> items = calculator.prorate(pro, basic, start, end, change);

        BigDecimal net = items.get(0).getAmount().add(items.get(1).getAmount());
        assertTrue(net.signum() < 0, "Downgrade mid-cycle should produce net credit");
        assertEquals(new BigDecimal("-10.00"), net);
    }

    @Test
    void change_atPeriodStart_proratesFullPrice() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);
        LocalDate change = start;

        List<InvoiceLineItem> items = calculator.prorate(basic, pro, start, end, change);

        assertEquals(new BigDecimal("-10.00"), items.get(0).getAmount());
        assertEquals(new BigDecimal("30.00"), items.get(1).getAmount());
    }

    @Test
    void change_onLastDay_proratesSingleDay() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);
        LocalDate change = LocalDate.of(2026, 1, 30); // 1 day remaining

        List<InvoiceLineItem> items = calculator.prorate(basic, pro, start, end, change);

        BigDecimal expectedCredit = new BigDecimal("10.00")
                .multiply(BigDecimal.ONE).divide(new BigDecimal("30"), 2, java.math.RoundingMode.HALF_UP)
                .negate();
        assertEquals(expectedCredit, items.get(0).getAmount());
    }

    @Test
    void change_afterPeriodEnd_throws() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);

        assertThrows(IllegalArgumentException.class,
                () -> calculator.prorate(basic, pro, start, end, end));
        assertThrows(IllegalArgumentException.class,
                () -> calculator.prorate(basic, pro, start, end, end.plusDays(1)));
    }

    @Test
    void cancellation_midCycle_creditsUnusedDays() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);
        LocalDate cancel = LocalDate.of(2026, 1, 21); // 10 days remaining of 30

        InvoiceLineItem credit = calculator.prorateCancellation(pro, start, end, cancel);

        assertEquals(InvoiceLineItem.LineItemType.CREDIT, credit.getType());
        assertEquals(new BigDecimal("-10.00"), credit.getAmount());
    }
}
