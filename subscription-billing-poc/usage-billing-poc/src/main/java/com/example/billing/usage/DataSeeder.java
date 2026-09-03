package com.example.billing.usage;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class DataSeeder implements CommandLineRunner {

    private final PricingTierRepository pricingRepo;

    public DataSeeder(PricingTierRepository pricingRepo) {
        this.pricingRepo = pricingRepo;
    }

    @Override
    public void run(String... args) {
        if (pricingRepo.count() > 0) return;

        // API calls: first 100K free, next 900K at $0.0010, next 9M at $0.0005, rest at $0.0002.
        pricingRepo.save(new PricingTier(UsageMetric.API_CALLS, 0,
                new BigDecimal("0"), new BigDecimal("100000"), new BigDecimal("0.0000")));
        pricingRepo.save(new PricingTier(UsageMetric.API_CALLS, 1,
                new BigDecimal("100000"), new BigDecimal("1000000"), new BigDecimal("0.0010")));
        pricingRepo.save(new PricingTier(UsageMetric.API_CALLS, 2,
                new BigDecimal("1000000"), new BigDecimal("10000000"), new BigDecimal("0.0005")));
        pricingRepo.save(new PricingTier(UsageMetric.API_CALLS, 3,
                new BigDecimal("10000000"), null, new BigDecimal("0.0002")));

        // Storage GB-hours: $0.02 per GB-hour flat.
        pricingRepo.save(new PricingTier(UsageMetric.STORAGE_GB_HOURS, 0,
                new BigDecimal("0"), null, new BigDecimal("0.0200")));
    }
}
