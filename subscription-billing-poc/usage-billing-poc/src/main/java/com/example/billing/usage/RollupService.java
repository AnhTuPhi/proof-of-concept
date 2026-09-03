package com.example.billing.usage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rolls up raw usage events into per-customer/metric/day totals. Idempotent: re-running
 * for a day overwrites the existing rollup so a late-arriving event is reflected.
 */
@Service
public class RollupService {

    private final UsageEventRepository eventRepo;
    private final DailyRollupRepository rollupRepo;

    public RollupService(UsageEventRepository eventRepo, DailyRollupRepository rollupRepo) {
        this.eventRepo = eventRepo;
        this.rollupRepo = rollupRepo;
    }

    /**
     * Rebuild rollups for a single customer on a single day. Returns the rollups written.
     */
    @Transactional
    public List<DailyRollup> rollup(String customerId, LocalDate day) {
        List<UsageEvent> events = eventRepo.findForRollup(customerId, day);
        Map<UsageMetric, Acc> byMetric = new HashMap<>();
        for (UsageEvent e : events) {
            byMetric.computeIfAbsent(e.getMetric(), m -> new Acc()).add(e.getQuantity());
        }

        return byMetric.entrySet().stream().map(entry -> {
            UsageMetric metric = entry.getKey();
            Acc acc = entry.getValue();
            DailyRollup existing = rollupRepo
                    .findByCustomerIdAndMetricAndUsageDay(customerId, metric, day)
                    .orElse(null);
            if (existing != null) {
                rollupRepo.delete(existing);
                rollupRepo.flush();
            }
            return rollupRepo.save(new DailyRollup(customerId, metric, day, acc.sum, acc.count));
        }).toList();
    }

    private static class Acc {
        BigDecimal sum = BigDecimal.ZERO;
        long count = 0;

        void add(BigDecimal q) {
            sum = sum.add(q);
            count++;
        }
    }
}
