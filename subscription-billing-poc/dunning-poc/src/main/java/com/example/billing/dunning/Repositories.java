package com.example.billing.dunning;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

interface PaymentMethodRepository extends JpaRepository<PaymentMethod, Long> {
}

interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    @Query("select s from Subscription s where s.status in ('ACTIVE','PAST_DUE') and s.nextRenewalOn <= :asOf")
    List<Subscription> findDueForRenewal(LocalDate asOf);

    @Query("select s from Subscription s where s.status = 'SUSPENDED' and s.suspendedOn is not null")
    List<Subscription> findSuspended();

    @Query("select s from Subscription s where s.status = 'PAST_DUE' and s.scheduledSuspensionOn is not null and s.scheduledSuspensionOn <= :asOf")
    List<Subscription> findPastDueDueForSuspension(LocalDate asOf);
}

interface RenewalAttemptRepository extends JpaRepository<RenewalAttempt, Long> {

    @Query("select a from RenewalAttempt a where a.subscriptionId = :subId order by a.attemptNumber asc")
    List<RenewalAttempt> findBySubscription(Long subId);

    @Query("select a from RenewalAttempt a where a.status = 'SCHEDULED' and a.scheduledOn <= :asOf")
    List<RenewalAttempt> findDueRetries(LocalDate asOf);
}

interface DunningEventRepository extends JpaRepository<DunningEvent, Long> {

    @Query("select e from DunningEvent e where e.subscriptionId = :subId order by e.occurredOn asc, e.id asc")
    List<DunningEvent> findBySubscription(Long subId);
}
