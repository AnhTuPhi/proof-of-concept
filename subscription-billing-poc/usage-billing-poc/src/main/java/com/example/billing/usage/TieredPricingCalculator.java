package com.example.billing.usage;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Snowflake/AWS-style graduated tier pricing. The first {tier[0].width} units are billed
 * at tier[0].price; the next {tier[1].width} at tier[1].price; etc. The last tier may have
 * a null end → unlimited. Returns one breakdown row per tier that contributed quantity.
 */
@Component
public class TieredPricingCalculator {

    public List<TierCharge> price(UsageMetric metric, BigDecimal totalUsage, List<PricingTier> tiers) {
        List<PricingTier> sorted = new ArrayList<>(tiers);
        sorted.sort((a, b) -> Integer.compare(a.getTierOrder(), b.getTierOrder()));

        List<TierCharge> charges = new ArrayList<>();
        BigDecimal remaining = totalUsage;
        for (PricingTier tier : sorted) {
            if (remaining.signum() <= 0) break;
            BigDecimal width = tier.width();
            BigDecimal qty = width == null ? remaining : remaining.min(width);
            BigDecimal amount = qty.multiply(tier.getPricePerUnit())
                    .setScale(4, RoundingMode.HALF_UP);
            if (qty.signum() > 0) {
                charges.add(new TierCharge(metric, tier.getTierOrder(),
                        tier.getTierStart(), tier.getTierEnd(),
                        qty, tier.getPricePerUnit(), amount));
            }
            remaining = remaining.subtract(qty);
        }
        return charges;
    }

    public record TierCharge(UsageMetric metric, int tierOrder, BigDecimal tierStart,
                             BigDecimal tierEnd, BigDecimal quantity,
                             BigDecimal pricePerUnit, BigDecimal amount) {
    }
}
