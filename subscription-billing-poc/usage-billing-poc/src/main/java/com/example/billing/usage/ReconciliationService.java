package com.example.billing.usage;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Compares aggregated rollups against the raw event log. Snowflake-style billing teams run
 * this nightly to catch dropped or double-counted events. Returns a diff per metric.
 */
@Service
public class ReconciliationService {

    private final UsageEventRepository eventRepo;
    private final DailyRollupRepository rollupRepo;

    public ReconciliationService(UsageEventRepository eventRepo, DailyRollupRepository rollupRepo) {
        this.eventRepo = eventRepo;
        this.rollupRepo = rollupRepo;
    }

    public ReconciliationReport reconcile(String customerId, LocalDate start, LocalDate endExclusive) {
        LocalDate endInclusive = endExclusive.minusDays(1);
        BigDecimal totalDelta = BigDecimal.ZERO;
        long totalEventDelta = 0;
        ReconciliationReport report = new ReconciliationReport(customerId, start, endExclusive);

        for (UsageMetric metric : UsageMetric.values()) {
            BigDecimal rawSum = eventRepo.sumQuantity(customerId, metric, start, endInclusive);
            long rawCount = eventRepo.countEvents(customerId, metric, start, endInclusive);
            BigDecimal rollupSum = rollupRepo.findInRange(customerId, metric, start, endInclusive)
                    .stream().map(DailyRollup::getTotalQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long rollupCount = rollupRepo.findInRange(customerId, metric, start, endInclusive)
                    .stream().mapToLong(DailyRollup::getEventCount).sum();

            BigDecimal qtyDelta = rawSum.subtract(rollupSum);
            long eventDelta = rawCount - rollupCount;
            totalDelta = totalDelta.add(qtyDelta.abs());
            totalEventDelta += Math.abs(eventDelta);
            report.lines.add(new ReconciliationLine(metric, rawSum, rollupSum, qtyDelta,
                    rawCount, rollupCount, eventDelta));
        }
        report.totalQuantityDelta = totalDelta;
        report.totalEventDelta = totalEventDelta;
        report.matches = totalDelta.signum() == 0 && totalEventDelta == 0;
        return report;
    }

    public static class ReconciliationReport {
        public final String customerId;
        public final LocalDate periodStart;
        public final LocalDate periodEnd;
        public final java.util.List<ReconciliationLine> lines = new java.util.ArrayList<>();
        public BigDecimal totalQuantityDelta = BigDecimal.ZERO;
        public long totalEventDelta = 0;
        public boolean matches = true;

        ReconciliationReport(String customerId, LocalDate periodStart, LocalDate periodEnd) {
            this.customerId = customerId;
            this.periodStart = periodStart;
            this.periodEnd = periodEnd;
        }
    }

    public record ReconciliationLine(
            UsageMetric metric,
            BigDecimal rawTotal,
            BigDecimal rollupTotal,
            BigDecimal quantityDelta,
            long rawEventCount,
            long rollupEventCount,
            long eventCountDelta) {
    }
}
