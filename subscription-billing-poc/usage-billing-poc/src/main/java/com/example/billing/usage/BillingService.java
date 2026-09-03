package com.example.billing.usage;

import com.example.billing.common.BillingException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Generates invoices from daily rollups. Source of truth is the rollup table, but every
 * tier line item carries its underlying quantity so the customer dashboard can match the
 * invoice to within +/- 0.0001.
 */
@Service
public class BillingService {

    private final DailyRollupRepository rollupRepo;
    private final PricingTierRepository pricingRepo;
    private final InvoiceRepository invoiceRepo;
    private final UsageEventRepository eventRepo;
    private final TieredPricingCalculator calculator;

    public BillingService(DailyRollupRepository rollupRepo,
                          PricingTierRepository pricingRepo,
                          InvoiceRepository invoiceRepo,
                          UsageEventRepository eventRepo,
                          TieredPricingCalculator calculator) {
        this.rollupRepo = rollupRepo;
        this.pricingRepo = pricingRepo;
        this.invoiceRepo = invoiceRepo;
        this.eventRepo = eventRepo;
        this.calculator = calculator;
    }

    @Transactional
    public Invoice generateInvoice(String customerId, LocalDate periodStart, LocalDate periodEnd) {
        if (!periodEnd.isAfter(periodStart)) {
            throw new BillingException("periodEnd must be after periodStart");
        }
        Invoice invoice = new Invoice(customerId, periodStart, periodEnd, "USD");

        for (UsageMetric metric : UsageMetric.values()) {
            BigDecimal total = sumRollup(customerId, metric, periodStart, periodEnd);
            if (total.signum() <= 0) continue;

            List<PricingTier> tiers = pricingRepo.findForMetric(metric);
            if (tiers.isEmpty()) {
                throw new BillingException("No pricing tiers configured for metric " + metric);
            }
            List<TieredPricingCalculator.TierCharge> charges = calculator.price(metric, total, tiers);
            for (TieredPricingCalculator.TierCharge c : charges) {
                String desc = String.format(
                        "%s tier %d (%s - %s @ %s)",
                        metric.name().toLowerCase().replace('_', ' '),
                        c.tierOrder(),
                        c.tierStart().toPlainString(),
                        c.tierEnd() == null ? "∞" : c.tierEnd().toPlainString(),
                        c.pricePerUnit().toPlainString());
                invoice.addLine(new InvoiceLineItem(desc, metric, c.quantity(),
                        c.pricePerUnit(), c.amount()));
            }
        }
        return invoiceRepo.save(invoice);
    }

    public BigDecimal sumRollup(String customerId, UsageMetric metric,
                                LocalDate start, LocalDate endExclusive) {
        return rollupRepo.findInRange(customerId, metric, start, endExclusive.minusDays(1))
                .stream()
                .map(DailyRollup::getTotalQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<Invoice> invoicesFor(String customerId) {
        return invoiceRepo.findByCustomer(customerId);
    }

    public List<PricingTier> allTiers() {
        List<PricingTier> all = new java.util.ArrayList<>();
        for (UsageMetric metric : UsageMetric.values()) {
            all.addAll(pricingRepo.findForMetric(metric));
        }
        return all;
    }

    public List<UsageEvent> recentEvents(int limit) {
        return eventRepo.findTop(org.springframework.data.domain.PageRequest.of(0, limit));
    }

    public List<DailyRollup> rollupsInRange(String customerId, LocalDate periodStart, LocalDate periodEnd) {
        List<DailyRollup> result = new java.util.ArrayList<>();
        LocalDate endInclusive = periodEnd.minusDays(1);
        for (UsageMetric metric : UsageMetric.values()) {
            result.addAll(rollupRepo.findInRange(customerId, metric, periodStart, endInclusive));
        }
        return result;
    }
}
