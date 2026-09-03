package com.example.fintech.fx;

import com.example.fintech.common.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MultiCurrencyTest {

    @Autowired FxService service;
    @Autowired FxRateProvider rateProvider;
    @Autowired FxPnlEntryRepository pnlRepo;

    @Test
    void purchaseInUSD_paidInVND_refundedLaterAtOriginalRate_PnLAbsorbed() {
        rateProvider.setRate(Currency.USD, Currency.VND, new BigDecimal("24000.00"));

        FxQuote quote = service.quote(Currency.USD, Currency.VND);
        FxPayment payment = service.payAgainstQuote(quote.getId(), new BigDecimal("10.00"));

        BigDecimal expectedSettlement = new BigDecimal("10.00")
                .multiply(quote.getRate())
                .setScale(0, java.math.RoundingMode.HALF_EVEN);
        assertEquals(0, payment.getSettlementAmount().compareTo(expectedSettlement),
                "settlement = presentment × locked rate, rounded to VND scale");

        rateProvider.setRate(Currency.USD, Currency.VND, new BigDecimal("26000.00"));

        FxService.RefundResult refund = service.refundOriginalCurrency(payment.getId(), new BigDecimal("10.00"));

        assertEquals(0, refund.refundedToCustomer().compareTo(new BigDecimal("10.00")),
                "customer is refunded the original USD amount, not VND");
        assertEquals(Currency.USD, refund.refundedToCustomerCurrency(),
                "refund currency must match the original presentment currency");
        assertEquals(0, refund.settlementCost().compareTo(expectedSettlement),
                "our settlement cost uses the LOCKED rate from the original payment");
        assertTrue(refund.fxPnlAbsorbed().signum() > 0,
                "VND weakened 24k→26k between purchase and refund: we eat a positive FX loss");
    }

    @Test
    void rateFluctuation_isolatedInFxPnLAccount_notMixedWithPaymentAmount() {
        rateProvider.setRate(Currency.USD, Currency.VND, new BigDecimal("24000.00"));
        FxQuote quote = service.quote(Currency.USD, Currency.VND);
        FxPayment payment = service.payAgainstQuote(quote.getId(), new BigDecimal("100.00"));

        rateProvider.setRate(Currency.USD, Currency.VND, new BigDecimal("23000.00"));
        service.refundOriginalCurrency(payment.getId(), new BigDecimal("100.00"));

        List<FxPnlEntry> pnl = pnlRepo.findByReferenceId(payment.getId());
        assertFalse(pnl.isEmpty(), "FX delta must be booked to a dedicated FX P&L account");
        assertTrue(pnl.stream().anyMatch(e -> "FX_DELTA_REFUND".equals(e.getKind())),
                "refund-time FX move must be tracked separately from payment-time margin");
    }

    @Test
    void freshQuote_isNotExpired_andHasFutureExpiry() {
        FxQuote q = service.quote(Currency.USD, Currency.VND);

        assertFalse(q.isExpired(), "freshly issued quote must not be expired");
        assertTrue(q.getExpiresAt().isAfter(q.getLockedAt()), "expiry must come after lock time");
        assertTrue(q.getExpiresAt().isAfter(java.time.Instant.now()),
                "fresh quote must remain valid for the configured TTL");
    }

    @Test
    void quote_appliesMarginBelowMarketRate() {
        rateProvider.setRate(Currency.USD, Currency.VND, new BigDecimal("24000.00"));
        FxQuote q = service.quote(Currency.USD, Currency.VND);

        assertTrue(q.getRate().compareTo(new BigDecimal("24000.00")) < 0,
                "quoted rate must include margin (less favorable than market for the customer)");
    }
}
