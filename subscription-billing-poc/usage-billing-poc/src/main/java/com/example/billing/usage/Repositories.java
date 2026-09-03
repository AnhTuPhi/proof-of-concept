package com.example.billing.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

interface UsageEventRepository extends JpaRepository<UsageEvent, Long> {

    Optional<UsageEvent> findByEventId(String eventId);

    @Query("select e from UsageEvent e order by e.id desc")
    List<UsageEvent> findTop(org.springframework.data.domain.Pageable page);

    @Query("""
            select coalesce(sum(e.quantity), 0)
              from UsageEvent e
             where e.customerId = :customerId
               and e.metric = :metric
               and e.usageDay between :start and :end
            """)
    BigDecimal sumQuantity(String customerId, UsageMetric metric, LocalDate start, LocalDate end);

    @Query("""
            select count(e)
              from UsageEvent e
             where e.customerId = :customerId
               and e.metric = :metric
               and e.usageDay between :start and :end
            """)
    long countEvents(String customerId, UsageMetric metric, LocalDate start, LocalDate end);

    @Query("""
            select e from UsageEvent e
             where e.customerId = :customerId
               and e.usageDay = :day
            """)
    List<UsageEvent> findForRollup(String customerId, LocalDate day);
}

interface DailyRollupRepository extends JpaRepository<DailyRollup, Long> {

    Optional<DailyRollup> findByCustomerIdAndMetricAndUsageDay(String customerId,
                                                              UsageMetric metric,
                                                              LocalDate usageDay);

    @Query("""
            select r from DailyRollup r
             where r.customerId = :customerId
               and r.metric = :metric
               and r.usageDay between :start and :end
            """)
    List<DailyRollup> findInRange(String customerId, UsageMetric metric,
                                  LocalDate start, LocalDate end);
}

interface PricingTierRepository extends JpaRepository<PricingTier, Long> {

    @Query("select t from PricingTier t where t.metric = :metric order by t.tierOrder asc")
    List<PricingTier> findForMetric(UsageMetric metric);
}

interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    @Query("select i from Invoice i where i.customerId = :customerId order by i.periodStart desc")
    List<Invoice> findByCustomer(String customerId);
}
