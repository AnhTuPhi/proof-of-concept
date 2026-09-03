package com.example.fintech.recon;

import com.example.fintech.common.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ReconciliationTest {

    @Autowired ReconciliationService service;
    @Autowired InternalTxnRepository internal;
    @Autowired ProviderTxnRepository provider;
    @Autowired BreakRepository breaks;

    @Test
    void runReconciliation_matchesHappyPath_andDetectsAllBreakTypes() {
        Instant now = Instant.parse("2026-06-17T10:00:00Z");

        seedMatched(now, "REF-HAPPY-1", new BigDecimal("100.00"), Currency.USD);
        seedMatched(now, "REF-HAPPY-2", new BigDecimal("50.00"), Currency.USD);
        internal.save(new InternalTxn("INT-MISSPROV", new BigDecimal("75.00"), Currency.USD, "REF-MISSPROV", now));
        provider.save(new ProviderTxn("PRV-MISSINT", new BigDecimal("25.00"), Currency.USD, "REF-MISSINT",
                now.plusSeconds(3600)));
        internal.save(new InternalTxn("INT-AMTDIFF", new BigDecimal("100.00"), Currency.USD, "REF-AMT", now));
        provider.save(new ProviderTxn("PRV-AMTDIFF", new BigDecimal("99.50"), Currency.USD, "REF-AMT",
                now.plusSeconds(3600)));
        internal.save(new InternalTxn("INT-CCYDIFF", new BigDecimal("100.00"), Currency.USD, "REF-CCY", now));
        provider.save(new ProviderTxn("PRV-CCYDIFF", new BigDecimal("100.00"), Currency.EUR, "REF-CCY",
                now.plusSeconds(3600)));

        ReconciliationService.ReconciliationReport report = service.runDailyReconciliation();

        assertEquals(2, report.matched(), "exactly 2 internal txns must auto-match");
        assertEquals(4, report.newBreaks(), "4 break categories must be raised");

        Map<ReconciliationBreak.BreakType, Long> byType = report.openBreaks().stream()
                .collect(java.util.stream.Collectors.groupingBy(ReconciliationBreak::getType,
                        java.util.stream.Collectors.counting()));

        assertEquals(1L, byType.get(ReconciliationBreak.BreakType.MISSING_AT_PROVIDER));
        assertEquals(1L, byType.get(ReconciliationBreak.BreakType.MISSING_AT_INTERNAL));
        assertEquals(1L, byType.get(ReconciliationBreak.BreakType.AMOUNT_MISMATCH));
        assertEquals(1L, byType.get(ReconciliationBreak.BreakType.CURRENCY_MISMATCH));
    }

    @Test
    void runReconciliation_isIdempotent_subsequentRunsDoNotReMatch() {
        Instant now = Instant.parse("2026-06-17T10:00:00Z");
        seedMatched(now, "REF-X", new BigDecimal("10.00"), Currency.USD);

        ReconciliationService.ReconciliationReport first = service.runDailyReconciliation();
        ReconciliationService.ReconciliationReport second = service.runDailyReconciliation();

        assertEquals(1, first.matched());
        assertEquals(0, second.matched(), "already-matched rows must not re-match on rerun");
    }

    private void seedMatched(Instant now, String ref, BigDecimal amount, Currency ccy) {
        internal.save(new InternalTxn("INT-" + ref, amount, ccy, ref, now));
        provider.save(new ProviderTxn("PRV-" + ref, amount, ccy, ref, now.plusSeconds(7200)));
    }
}
