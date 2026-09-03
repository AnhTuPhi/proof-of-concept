package com.example.billing.dunning;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DunningFlowTest {

    @Autowired
    SubscriptionRepository subRepo;
    @Autowired
    PaymentMethodRepository pmRepo;
    @Autowired
    RenewalAttemptRepository attemptRepo;
    @Autowired
    DunningEventRepository eventRepo;
    @Autowired
    DunningService service;
    @Autowired
    NotificationGateway notifier;

    @BeforeEach
    void wipe() {
        notifier.reset();
        // Each test starts from a clean slate so state from a previous test (pending
        // retries, PAST_DUE subs) doesn't leak into the next.
        eventRepo.deleteAll();
        attemptRepo.deleteAll();
        subRepo.deleteAll();
        pmRepo.deleteAll();
    }

    private Subscription newSub(String customer, boolean cardWorks, LocalDate renewal) {
        PaymentMethod pm = pmRepo.save(new PaymentMethod(customer, "VISA", "4242",
                LocalDate.of(2030, 12, 31)));
        if (!cardWorks) {
            pm.forceDecline("card_declined");
            pmRepo.save(pm);
        }
        return subRepo.save(new Subscription(customer, "Pro", new BigDecimal("30.00"),
                pm.getId(), renewal));
    }

    @Test
    void healthyChargeAdvancesRenewal() {
        LocalDate today = LocalDate.of(2026, 6, 1);
        Subscription sub = newSub("happy", true, today);

        service.attemptInitialRenewal(sub.getId(), today);

        Subscription reloaded = subRepo.findById(sub.getId()).orElseThrow();
        assertEquals(Subscription.Status.ACTIVE, reloaded.getStatus());
        assertEquals(today.plusMonths(1), reloaded.getNextRenewalOn());
    }

    @Test
    void failedRenewalEntersPastDueAndSchedulesRetry() {
        LocalDate today = LocalDate.of(2026, 6, 1);
        Subscription sub = newSub("bad-1", false, today);

        service.attemptInitialRenewal(sub.getId(), today);

        Subscription reloaded = subRepo.findById(sub.getId()).orElseThrow();
        assertEquals(Subscription.Status.PAST_DUE, reloaded.getStatus());

        List<RenewalAttempt> attempts = service.attempts(sub.getId());
        assertEquals(2, attempts.size());
        assertEquals(RenewalAttempt.Status.FAILED, attempts.get(0).getStatus());
        assertEquals(RenewalAttempt.Status.SCHEDULED, attempts.get(1).getStatus());
        assertEquals(today.plusDays(3), attempts.get(1).getScheduledOn(),
                "First retry scheduled 3 days out per default retry-offsets-days");

        assertEquals(1, notifier.sentFor(sub.getId()).size());
        assertEquals("retry_scheduled", notifier.sentFor(sub.getId()).get(0).template());
    }

    @Test
    void allRetriesExhaustedSchedulesSuspensionAfterGracePeriod() {
        LocalDate day0 = LocalDate.of(2026, 6, 1);
        Subscription sub = newSub("bad-2", false, day0);

        service.attemptInitialRenewal(sub.getId(), day0); // attempt #1, sched #2 at day 3
        service.tick(day0.plusDays(3));                   // attempt #2, sched #3 at day 8
        service.tick(day0.plusDays(8));                   // attempt #3, sched #4 at day 15
        service.tick(day0.plusDays(15));                  // attempt #4, retries exhausted

        Subscription reloaded = subRepo.findById(sub.getId()).orElseThrow();
        assertEquals(Subscription.Status.PAST_DUE, reloaded.getStatus());
        assertEquals(day0.plusDays(15 + 2), reloaded.getScheduledSuspensionOn(),
                "Suspension scheduled at last failure + grace period (default 2 days)");

        // Tick on grace day → SUSPENDED.
        service.tick(day0.plusDays(17));
        reloaded = subRepo.findById(sub.getId()).orElseThrow();
        assertEquals(Subscription.Status.SUSPENDED, reloaded.getStatus());
        assertEquals(day0.plusDays(17), reloaded.getSuspendedOn());

        // After 14 more days → CANCELED.
        service.tick(day0.plusDays(17 + 14));
        reloaded = subRepo.findById(sub.getId()).orElseThrow();
        assertEquals(Subscription.Status.CANCELED, reloaded.getStatus());

        List<DunningEvent> timeline = service.timeline(sub.getId());
        long failedCount = timeline.stream()
                .filter(e -> e.getType() == DunningEvent.Type.PAYMENT_FAILED).count();
        assertEquals(4, failedCount, "Four failed charge events across initial + 3 retries");
        assertTrue(notifier.sentFor(sub.getId()).stream()
                .anyMatch(n -> n.template().equals("final_warning")));
        assertTrue(notifier.sentFor(sub.getId()).stream()
                .anyMatch(n -> n.template().equals("service_suspended")));
    }

    @Test
    void updatingPaymentMethodSettlesPastDueAndReactivates() {
        LocalDate day0 = LocalDate.of(2026, 6, 1);
        Subscription sub = newSub("bad-3", false, day0);

        service.attemptInitialRenewal(sub.getId(), day0);
        Subscription past = subRepo.findById(sub.getId()).orElseThrow();
        assertEquals(Subscription.Status.PAST_DUE, past.getStatus());

        // Customer fixes their card on day 2.
        service.updatePaymentMethod(sub.getId(), "VISA", "4242",
                LocalDate.of(2031, 12, 31), day0.plusDays(2));

        Subscription reloaded = subRepo.findById(sub.getId()).orElseThrow();
        assertEquals(Subscription.Status.ACTIVE, reloaded.getStatus());
        assertEquals(day0.plusDays(2).plusMonths(1), reloaded.getNextRenewalOn());
        assertNull(reloaded.getScheduledSuspensionOn());

        // Pending retries are auto-canceled (marked SUCCEEDED by the settlement attempt).
        assertTrue(service.attempts(sub.getId()).stream()
                .noneMatch(a -> a.getStatus() == RenewalAttempt.Status.SCHEDULED));
    }

    @Test
    void tickIsIdempotentOnSameDay() {
        LocalDate today = LocalDate.of(2026, 6, 1);
        Subscription sub = newSub("idempotent", false, today);

        service.attemptInitialRenewal(sub.getId(), today);
        DunningService.ProcessSummary first = service.tick(today.plusDays(3));
        DunningService.ProcessSummary second = service.tick(today.plusDays(3));

        assertEquals(1, first.retriesRun());
        assertEquals(0, second.retriesRun(), "Re-ticking same day runs nothing new");
    }
}
