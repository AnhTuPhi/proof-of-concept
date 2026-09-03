package com.example.billing.usage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class UsageFlowTest {

    @Autowired
    IngestionService ingestion;
    @Autowired
    RollupService rollupService;
    @Autowired
    BillingService billing;
    @Autowired
    ReconciliationService reconciliation;

    private Instant on(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    @Test
    void duplicateEventIdIsDeduped() {
        Instant t = on(LocalDate.of(2026, 6, 1));
        IngestionService.IngestionResult first = ingestion.ingest(
                "evt-1", "cust-A", UsageMetric.API_CALLS, new BigDecimal("100"), t);
        IngestionService.IngestionResult second = ingestion.ingest(
                "evt-1", "cust-A", UsageMetric.API_CALLS, new BigDecimal("100"), t);

        assertFalse(first.duplicate());
        assertTrue(second.duplicate());
        assertEquals(first.event().getId(), second.event().getId());
    }

    @Test
    void invoiceMatchesExpectedTieredTotal() {
        // 1.4M calls + 200 GB-hours across June 2026 → $1100 + $4 = $1104.
        LocalDate day = LocalDate.of(2026, 6, 5);
        ingestEvents(day, 1_400_000L);

        ingestion.ingest(UUID.randomUUID().toString(), "cust-B",
                UsageMetric.STORAGE_GB_HOURS, new BigDecimal("200"), on(day));

        rollupService.rollup("cust-B", day);

        Invoice invoice = billing.generateInvoice(
                "cust-B", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1));

        assertEquals(new BigDecimal("1104.00"), invoice.totalAmount());
        assertEquals(4, invoice.getLineItems().size()); // 3 API tiers + 1 storage tier
    }

    @Test
    void reconciliationMatchesWhenRollupIsFresh() {
        LocalDate day = LocalDate.of(2026, 6, 10);
        for (int i = 0; i < 5; i++) {
            ingestion.ingest("rec-" + i, "cust-C", UsageMetric.API_CALLS,
                    new BigDecimal("1000"), on(day));
        }
        rollupService.rollup("cust-C", day);

        var report = reconciliation.reconcile("cust-C",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1));

        assertTrue(report.matches);
        assertEquals(0, report.totalEventDelta);
    }

    @Test
    void reconciliationCatchesMissingRollup() {
        LocalDate day = LocalDate.of(2026, 6, 12);
        ingestion.ingest("late-1", "cust-D", UsageMetric.API_CALLS,
                new BigDecimal("500"), on(day));
        rollupService.rollup("cust-D", day);

        // A new event arrives AFTER the rollup ran.
        ingestion.ingest("late-2", "cust-D", UsageMetric.API_CALLS,
                new BigDecimal("700"), on(day));

        var report = reconciliation.reconcile("cust-D",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1));

        assertFalse(report.matches);
        assertEquals(1, report.totalEventDelta);
        assertEquals(0, new BigDecimal("700").compareTo(
                report.lines.stream()
                        .filter(l -> l.metric() == UsageMetric.API_CALLS)
                        .findFirst().orElseThrow().quantityDelta()),
                "Late event of 700 should show up as the quantity delta");

        // Re-running rollup catches up.
        rollupService.rollup("cust-D", day);
        var fixed = reconciliation.reconcile("cust-D",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1));
        assertTrue(fixed.matches);
    }

    private void ingestEvents(LocalDate day, long totalCalls) {
        // Ingest as a few big chunks to keep the test fast.
        long perChunk = 100_000L;
        long remaining = totalCalls;
        int seq = 0;
        while (remaining > 0) {
            long n = Math.min(perChunk, remaining);
            ingestion.ingest("bulk-" + day + "-" + (seq++), "cust-B", UsageMetric.API_CALLS,
                    BigDecimal.valueOf(n), on(day));
            remaining -= n;
        }
    }
}
