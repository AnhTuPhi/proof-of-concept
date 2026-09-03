package com.example.billing.dunning;

import com.example.billing.common.BillingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class DunningService {

    private static final Logger log = LoggerFactory.getLogger(DunningService.class);

    private final SubscriptionRepository subRepo;
    private final PaymentMethodRepository pmRepo;
    private final RenewalAttemptRepository attemptRepo;
    private final DunningEventRepository eventRepo;
    private final ChargeGateway gateway;
    private final NotificationGateway notifier;
    private final DunningProperties props;

    public DunningService(SubscriptionRepository subRepo,
                          PaymentMethodRepository pmRepo,
                          RenewalAttemptRepository attemptRepo,
                          DunningEventRepository eventRepo,
                          ChargeGateway gateway,
                          NotificationGateway notifier,
                          DunningProperties props) {
        this.subRepo = subRepo;
        this.pmRepo = pmRepo;
        this.attemptRepo = attemptRepo;
        this.eventRepo = eventRepo;
        this.gateway = gateway;
        this.notifier = notifier;
        this.props = props;
    }

    /**
     * Try to renew on the given day. Initial attempt; on failure schedules retries.
     */
    @Transactional
    public RenewalAttempt attemptInitialRenewal(Long subscriptionId, LocalDate today) {
        Subscription sub = require(subscriptionId);
        if (sub.getStatus() == Subscription.Status.CANCELED) {
            throw new BillingException("Subscription is canceled");
        }
        if (today.isBefore(sub.getNextRenewalOn())) {
            throw new BillingException("Renewal not yet due: " + sub.getNextRenewalOn());
        }
        return runAttempt(sub, 1, today);
    }

    /**
     * Tick the clock. Runs all scheduled retries due on or before today, then
     * (a) suspends PAST_DUE subs whose grace period has elapsed, and
     * (b) cancels SUSPENDED subs whose suspension window has elapsed.
     * Idempotent: re-running for the same day is a no-op once everything's processed.
     */
    @Transactional
    public ProcessSummary tick(LocalDate today) {
        int retries = 0;
        int suspensions = 0;
        int cancellations = 0;

        for (RenewalAttempt due : attemptRepo.findDueRetries(today)) {
            Subscription sub = subRepo.findById(due.getSubscriptionId()).orElseThrow();
            if (sub.getStatus() == Subscription.Status.CANCELED
                    || sub.getStatus() == Subscription.Status.SUSPENDED) {
                continue;
            }
            executeAttempt(sub, due, today);
            retries++;
        }

        // PAST_DUE -> SUSPENDED once grace period has elapsed.
        for (Subscription sub : subRepo.findPastDueDueForSuspension(today)) {
            sub.suspend(today);
            subRepo.save(sub);
            eventRepo.save(new DunningEvent(sub.getId(), DunningEvent.Type.SUSPENDED, today,
                    "Grace period of " + props.gracePeriodDays() + " days elapsed"));
            notifier.send(sub.getId(), "service_suspended", sub.getCustomerId() + "@example.com");
            eventRepo.save(new DunningEvent(sub.getId(), DunningEvent.Type.EMAIL_SENT, today,
                    "template=service_suspended"));
            suspensions++;
        }

        // SUSPENDED -> CANCELED past the suspension window.
        for (Subscription sub : subRepo.findSuspended()) {
            if (sub.getSuspendedOn() == null) continue;
            LocalDate cutoff = sub.getSuspendedOn().plusDays(props.suspensionWindowDays());
            if (!today.isBefore(cutoff)) {
                sub.cancel(today);
                subRepo.save(sub);
                eventRepo.save(new DunningEvent(sub.getId(), DunningEvent.Type.CANCELED, today,
                        "Suspension window of " + props.suspensionWindowDays() + " days elapsed"));
                cancellations++;
            }
        }
        return new ProcessSummary(today, retries, suspensions, cancellations);
    }

    /**
     * Customer updates their payment method. If the sub is PAST_DUE or SUSPENDED, try
     * to settle immediately.
     */
    @Transactional
    public Subscription updatePaymentMethod(Long subscriptionId, String brand, String last4,
                                            LocalDate expiresOn, LocalDate today) {
        Subscription sub = require(subscriptionId);
        if (sub.getStatus() == Subscription.Status.CANCELED) {
            throw new BillingException("Cannot update payment on a canceled subscription; resubscribe instead");
        }
        PaymentMethod pm = pmRepo.findById(sub.getPaymentMethodId())
                .orElseThrow(() -> new BillingException("Missing payment method"));
        pm.replaceWith(brand, last4, expiresOn);
        pmRepo.save(pm);

        if (sub.getStatus() == Subscription.Status.PAST_DUE
                || sub.getStatus() == Subscription.Status.SUSPENDED) {
            settleImmediately(sub, today);
        }
        return subRepo.save(sub);
    }

    private void settleImmediately(Subscription sub, LocalDate today) {
        int nextAttemptNum = nextAttemptNumber(sub.getId());
        RenewalAttempt attempt = new RenewalAttempt(sub.getId(), nextAttemptNum, today);
        attemptRepo.save(attempt);
        executeAttempt(sub, attempt, today);
    }

    private RenewalAttempt runAttempt(Subscription sub, int attemptNumber, LocalDate today) {
        RenewalAttempt attempt = attemptRepo.save(new RenewalAttempt(sub.getId(), attemptNumber, today));
        return executeAttempt(sub, attempt, today);
    }

    private RenewalAttempt executeAttempt(Subscription sub, RenewalAttempt attempt, LocalDate today) {
        PaymentMethod pm = pmRepo.findById(sub.getPaymentMethodId()).orElse(null);
        ChargeGateway.ChargeOutcome outcome =
                gateway.charge(pm, sub.getMonthlyAmount(), sub.getCurrency(), today);

        if (outcome.success()) {
            attempt.markSucceeded(today);
            attemptRepo.save(attempt);
            // Cancel any other pending retries for this subscription.
            attemptRepo.findBySubscription(sub.getId()).stream()
                    .filter(a -> a.getStatus() == RenewalAttempt.Status.SCHEDULED
                            && !a.getId().equals(attempt.getId()))
                    .forEach(a -> {
                        a.markSucceeded(today);
                        attemptRepo.save(a);
                    });
            boolean wasRecovery = sub.getStatus() != Subscription.Status.ACTIVE;
            if (wasRecovery) {
                sub.reactivate(today.plusMonths(1));
                eventRepo.save(new DunningEvent(sub.getId(), DunningEvent.Type.REACTIVATED, today,
                        "Payment recovered after dunning"));
            } else {
                sub.advanceRenewal();
            }
            subRepo.save(sub);
            eventRepo.save(new DunningEvent(sub.getId(), DunningEvent.Type.PAYMENT_SUCCEEDED, today,
                    "ref=" + outcome.reference()));
            log.info("Charge succeeded for sub={} attempt={} ref={}",
                    sub.getId(), attempt.getAttemptNumber(), outcome.reference());
            return attempt;
        }

        attempt.markFailed(today, outcome.reason());
        attemptRepo.save(attempt);
        eventRepo.save(new DunningEvent(sub.getId(), DunningEvent.Type.PAYMENT_FAILED, today,
                "reason=" + outcome.reason() + " attempt=" + attempt.getAttemptNumber()));

        // Mark sub PAST_DUE on first failure of a cycle.
        if (sub.getStatus() == Subscription.Status.ACTIVE) {
            sub.enterPastDue();
            subRepo.save(sub);
        }

        // Decide what to do next. attemptNumber 1 means the original due-date charge;
        // offsets[0] schedules attempt 2, offsets[1] schedules attempt 3, etc.
        int totalRetries = props.retryOffsetsDays().size();
        int retriesUsed = attempt.getAttemptNumber() - 1;
        if (retriesUsed < totalRetries) {
            int offsetIdx = retriesUsed;
            LocalDate scheduled = today.plusDays(props.retryOffsetsDays().get(offsetIdx));
            RenewalAttempt next = attemptRepo.save(
                    new RenewalAttempt(sub.getId(), attempt.getAttemptNumber() + 1, scheduled));
            eventRepo.save(new DunningEvent(sub.getId(), DunningEvent.Type.RETRY_SCHEDULED, today,
                    "next attempt #" + next.getAttemptNumber() + " on " + scheduled));
            boolean lastRetry = retriesUsed == totalRetries - 1;
            String template = lastRetry ? "final_warning" : "retry_scheduled";
            notifier.send(sub.getId(), template, sub.getCustomerId() + "@example.com");
            eventRepo.save(new DunningEvent(sub.getId(), DunningEvent.Type.EMAIL_SENT, today,
                    "template=" + template));
        } else {
            // All retries exhausted: schedule suspension at end of grace period.
            // tick() will flip the subscription to SUSPENDED on or after that day.
            LocalDate suspendOn = today.plusDays(props.gracePeriodDays());
            sub.scheduleSuspension(suspendOn);
            subRepo.save(sub);
            if (props.gracePeriodDays() > 0) {
                eventRepo.save(new DunningEvent(sub.getId(), DunningEvent.Type.RETRY_SCHEDULED, today,
                        "Retries exhausted; grace period until " + suspendOn));
                notifier.send(sub.getId(), "grace_period", sub.getCustomerId() + "@example.com");
                eventRepo.save(new DunningEvent(sub.getId(), DunningEvent.Type.EMAIL_SENT, today,
                        "template=grace_period"));
            }
        }
        log.info("Charge failed for sub={} attempt={} reason={}",
                sub.getId(), attempt.getAttemptNumber(), outcome.reason());
        return attempt;
    }

    private int nextAttemptNumber(Long subId) {
        return attemptRepo.findBySubscription(subId).stream()
                .mapToInt(RenewalAttempt::getAttemptNumber).max().orElse(0) + 1;
    }

    private Subscription require(Long id) {
        return subRepo.findById(id)
                .orElseThrow(() -> new BillingException("Unknown subscription: " + id));
    }

    public List<DunningEvent> timeline(Long subscriptionId) {
        return eventRepo.findBySubscription(subscriptionId);
    }

    public List<RenewalAttempt> attempts(Long subscriptionId) {
        return attemptRepo.findBySubscription(subscriptionId);
    }

    public List<Subscription> allSubscriptions() {
        return subRepo.findAll();
    }

    public PaymentMethod paymentMethod(Long id) {
        return pmRepo.findById(id)
                .orElseThrow(() -> new BillingException("Unknown payment method: " + id));
    }

    public record ProcessSummary(LocalDate runAt, int retriesRun, int suspended, int canceled) {
    }
}
