package com.example.billing.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "daily_rollups",
        uniqueConstraints = @UniqueConstraint(name = "ux_rollup_dim",
                columnNames = {"customer_id", "metric", "usage_day"}))
public class DailyRollup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UsageMetric metric;

    @Column(name = "usage_day", nullable = false)
    private LocalDate usageDay;

    @Column(name = "total_quantity", nullable = false, precision = 20, scale = 4)
    private BigDecimal totalQuantity;

    @Column(name = "event_count", nullable = false)
    private long eventCount;

    @Version
    private Long version;

    protected DailyRollup() {
    }

    public DailyRollup(String customerId, UsageMetric metric, LocalDate usageDay,
                       BigDecimal totalQuantity, long eventCount) {
        this.customerId = customerId;
        this.metric = metric;
        this.usageDay = usageDay;
        this.totalQuantity = totalQuantity;
        this.eventCount = eventCount;
    }

    public Long getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public UsageMetric getMetric() {
        return metric;
    }

    public LocalDate getUsageDay() {
        return usageDay;
    }

    public BigDecimal getTotalQuantity() {
        return totalQuantity;
    }

    public long getEventCount() {
        return eventCount;
    }
}
