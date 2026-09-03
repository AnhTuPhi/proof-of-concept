package com.example.billing.usage;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TieredPricingCalculatorTest {

    private final TieredPricingCalculator calc = new TieredPricingCalculator();

    private final List<PricingTier> apiTiers = List.of(
            new PricingTier(UsageMetric.API_CALLS, 0,
                    new BigDecimal("0"), new BigDecimal("100000"), new BigDecimal("0.0000")),
            new PricingTier(UsageMetric.API_CALLS, 1,
                    new BigDecimal("100000"), new BigDecimal("1000000"), new BigDecimal("0.0010")),
            new PricingTier(UsageMetric.API_CALLS, 2,
                    new BigDecimal("1000000"), new BigDecimal("10000000"), new BigDecimal("0.0005")),
            new PricingTier(UsageMetric.API_CALLS, 3,
                    new BigDecimal("10000000"), null, new BigDecimal("0.0002"))
    );

    @Test
    void belowFirstTierIsFree() {
        var charges = calc.price(UsageMetric.API_CALLS, new BigDecimal("50000"), apiTiers);
        assertEquals(1, charges.size());
        assertEquals(new BigDecimal("0.0000"), charges.get(0).amount());
    }

    @Test
    void straddlesTwoTiersCorrectly() {
        // 1.4M calls: 100K free, 900K @ $0.001 = $900, 400K @ $0.0005 = $200 → $1100.
        var charges = calc.price(UsageMetric.API_CALLS, new BigDecimal("1400000"), apiTiers);
        assertEquals(3, charges.size());
        assertEquals(new BigDecimal("0.0000"), charges.get(0).amount());
        assertEquals(new BigDecimal("900.0000"), charges.get(1).amount());
        assertEquals(new BigDecimal("200.0000"), charges.get(2).amount());

        BigDecimal total = charges.stream().map(TieredPricingCalculator.TierCharge::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("1100.0000"), total);
    }

    @Test
    void overflowsIntoUnboundedTier() {
        // 20M calls: 100K free, 900K @ $0.001 = $900, 9M @ $0.0005 = $4500,
        // 10M @ $0.0002 = $2000 → $7400.
        var charges = calc.price(UsageMetric.API_CALLS, new BigDecimal("20000000"), apiTiers);
        BigDecimal total = charges.stream().map(TieredPricingCalculator.TierCharge::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("7400.0000"), total);
    }

    @Test
    void zeroUsageProducesNoCharges() {
        var charges = calc.price(UsageMetric.API_CALLS, BigDecimal.ZERO, apiTiers);
        assertEquals(0, charges.size());
    }
}
