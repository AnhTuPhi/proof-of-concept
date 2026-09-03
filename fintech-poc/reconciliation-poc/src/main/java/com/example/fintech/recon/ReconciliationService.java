package com.example.fintech.recon;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
public class ReconciliationService {

    private static final Duration MATCH_WINDOW = Duration.ofHours(48);

    private final InternalTxnRepository internal;
    private final ProviderTxnRepository provider;
    private final BreakRepository breaks;

    public ReconciliationService(InternalTxnRepository internal,
                                 ProviderTxnRepository provider,
                                 BreakRepository breaks) {
        this.internal = internal;
        this.provider = provider;
        this.breaks = breaks;
    }

    @Transactional
    public ReconciliationReport runDailyReconciliation() {
        int matched = 0;
        int newBreaks = 0;

        for (InternalTxn it : internal.findByMatched(false)) {
            Optional<ProviderTxn> match = provider.findByProviderRef(it.getProviderRef());
            if (match.isEmpty()) {
                breaks.save(new ReconciliationBreak(
                        ReconciliationBreak.BreakType.MISSING_AT_PROVIDER,
                        it.getId(), null,
                        "Internal txn " + it.getId() + " has no provider settlement"));
                newBreaks++;
                continue;
            }
            ProviderTxn pt = match.get();

            if (it.getCurrency() != pt.getCurrency()) {
                breaks.save(new ReconciliationBreak(
                        ReconciliationBreak.BreakType.CURRENCY_MISMATCH,
                        it.getId(), pt.getId(),
                        "Currency: internal=" + it.getCurrency() + " provider=" + pt.getCurrency()));
                newBreaks++;
                continue;
            }

            if (it.getAmount().compareTo(pt.getAmount()) != 0) {
                BigDecimal diff = it.getAmount().subtract(pt.getAmount()).abs();
                breaks.save(new ReconciliationBreak(
                        ReconciliationBreak.BreakType.AMOUNT_MISMATCH,
                        it.getId(), pt.getId(),
                        "Amount diff: " + diff + " " + it.getCurrency()));
                newBreaks++;
                continue;
            }

            Duration gap = Duration.between(it.getOccurredAt(), pt.getSettledAt()).abs();
            if (gap.compareTo(MATCH_WINDOW) > 0) {
                breaks.save(new ReconciliationBreak(
                        ReconciliationBreak.BreakType.AMOUNT_MISMATCH,
                        it.getId(), pt.getId(),
                        "Outside match window: gap=" + gap.toHours() + "h"));
                newBreaks++;
                continue;
            }

            it.markMatched();
            pt.markMatched();
            internal.save(it);
            provider.save(pt);
            matched++;
        }

        for (ProviderTxn pt : provider.findByMatched(false)) {
            if (internal.findByProviderRef(pt.getProviderRef()).isEmpty()) {
                breaks.save(new ReconciliationBreak(
                        ReconciliationBreak.BreakType.MISSING_AT_INTERNAL,
                        null, pt.getId(),
                        "Provider settled txn " + pt.getId() + " has no internal record"));
                newBreaks++;
            }
        }

        return new ReconciliationReport(matched, newBreaks, breaks.findByStatus(ReconciliationBreak.BreakStatus.OPEN));
    }

    public record ReconciliationReport(int matched, int newBreaks, List<ReconciliationBreak> openBreaks) {}
}
