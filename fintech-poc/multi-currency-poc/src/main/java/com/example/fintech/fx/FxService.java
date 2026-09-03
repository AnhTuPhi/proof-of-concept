package com.example.fintech.fx;

import com.example.fintech.common.Currency;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class FxService {

    private static final BigDecimal MARGIN = new BigDecimal("0.005");

    private final FxRateProvider rateProvider;
    private final FxQuoteRepository quotes;
    private final FxPaymentRepository payments;
    private final FxPnlEntryRepository pnl;

    public FxService(FxRateProvider rateProvider,
                     FxQuoteRepository quotes,
                     FxPaymentRepository payments,
                     FxPnlEntryRepository pnl) {
        this.rateProvider = rateProvider;
        this.quotes = quotes;
        this.payments = payments;
        this.pnl = pnl;
    }

    @Transactional
    public FxQuote quote(Currency from, Currency to) {
        BigDecimal raw = rateProvider.currentRate(from, to);
        BigDecimal withMargin = raw.multiply(BigDecimal.ONE.subtract(MARGIN))
                .setScale(8, RoundingMode.HALF_EVEN);
        return quotes.save(new FxQuote(from, to, withMargin));
    }

    @Transactional
    public FxPayment payAgainstQuote(String quoteId, BigDecimal presentmentAmount) {
        FxQuote q = quotes.findById(quoteId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown quote " + quoteId));
        if (q.isExpired()) {
            throw new IllegalStateException("Quote " + quoteId + " expired at " + q.getExpiresAt());
        }

        FxPayment payment = new FxPayment(q.getFrom(), presentmentAmount, q.getTo(), q.getRate(), q.getId());
        payments.save(payment);

        BigDecimal rawRate = rateProvider.currentRate(q.getFrom(), q.getTo());
        BigDecimal rawSettlement = presentmentAmount.multiply(rawRate)
                .setScale(q.getTo().scale(), RoundingMode.HALF_EVEN);
        BigDecimal margin = rawSettlement.subtract(payment.getSettlementAmount());
        if (margin.signum() != 0) {
            pnl.save(new FxPnlEntry(payment.getId(), "MARGIN", q.getTo(), margin));
        }
        return payment;
    }

    @Transactional
    public RefundResult refundOriginalCurrency(String paymentId, BigDecimal presentmentRefundAmount) {
        FxPayment original = payments.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment " + paymentId));

        if (presentmentRefundAmount.compareTo(original.getPresentmentAmount()) > 0) {
            throw new IllegalArgumentException("Refund cannot exceed original presentment amount");
        }

        BigDecimal settlementRefund = presentmentRefundAmount.multiply(original.getLockedRate())
                .setScale(original.getSettlementCurrency().scale(), RoundingMode.HALF_EVEN);

        BigDecimal currentRate = rateProvider.currentRate(original.getPresentmentCurrency(),
                original.getSettlementCurrency());
        BigDecimal settlementAtMarketRate = presentmentRefundAmount.multiply(currentRate)
                .setScale(original.getSettlementCurrency().scale(), RoundingMode.HALF_EVEN);
        BigDecimal fxDelta = settlementAtMarketRate.subtract(settlementRefund);

        if (fxDelta.signum() != 0) {
            pnl.save(new FxPnlEntry(original.getId(), "FX_DELTA_REFUND",
                    original.getSettlementCurrency(), fxDelta));
        }

        return new RefundResult(presentmentRefundAmount, original.getPresentmentCurrency(),
                settlementRefund, original.getSettlementCurrency(),
                original.getLockedRate(), currentRate, fxDelta);
    }

    public record RefundResult(
            BigDecimal refundedToCustomer,
            Currency refundedToCustomerCurrency,
            BigDecimal settlementCost,
            Currency settlementCurrency,
            BigDecimal originalRate,
            BigDecimal currentMarketRate,
            BigDecimal fxPnlAbsorbed) {}
}
