package com.example.billing.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Tiered pricing for a single metric. Tiers are exclusive ranges:
 * the i-th tier covers (cumulativeStart_{i-1}, cumulativeStart_i] in units.
 * The final tier has tierEnd == null meaning "unlimited".
 */
@Entity
@Table(name = "pricing_tiers")
public class PricingTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UsageMetric metric;

    @Column(name = "tier_order", nullable = false)
    private int tierOrder;

    /** Inclusive lower bound (cumulative units). */
    @Column(name = "tier_start", nullable = false, precision = 20, scale = 4)
    private BigDecimal tierStart;

    /** Exclusive upper bound (cumulative units). Null = unlimited. */
    @Column(name = "tier_end", precision = 20, scale = 4)
    private BigDecimal tierEnd;

    @Column(name = "price_per_unit", nullable = false, precision = 19, scale = 8)
    private BigDecimal pricePerUnit;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    protected PricingTier() {
    }

    public PricingTier(UsageMetric metric, int tierOrder, BigDecimal tierStart,
                       BigDecimal tierEnd, BigDecimal pricePerUnit) {
        if (tierEnd != null && tierEnd.compareTo(tierStart) <= 0) {
            throw new IllegalArgumentException("tierEnd must be greater than tierStart");
        }
        this.metric = metric;
        this.tierOrder = tierOrder;
        this.tierStart = tierStart;
        this.tierEnd = tierEnd;
        this.pricePerUnit = pricePerUnit;
    }

    /** Width of this tier in units, or null if unbounded. */
    public BigDecimal width() {
        return tierEnd == null ? null : tierEnd.subtract(tierStart);
    }

    public Long getId() {
        return id;
    }

    public UsageMetric getMetric() {
        return metric;
    }

    public int getTierOrder() {
        return tierOrder;
    }

    public BigDecimal getTierStart() {
        return tierStart;
    }

    public BigDecimal getTierEnd() {
        return tierEnd;
    }

    public BigDecimal getPricePerUnit() {
        return pricePerUnit;
    }

    public String getCurrency() {
        return currency;
    }
}
