package com.example.billing.proration;

import com.example.billing.common.BillingException;
import com.example.billing.common.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class SubscriptionService {

    private final PlanRepository planRepo;
    private final SubscriptionRepository subRepo;
    private final InvoiceRepository invoiceRepo;
    private final CustomerCreditRepository creditRepo;
    private final ProrationCalculator calculator;

    public SubscriptionService(PlanRepository planRepo,
                               SubscriptionRepository subRepo,
                               InvoiceRepository invoiceRepo,
                               CustomerCreditRepository creditRepo,
                               ProrationCalculator calculator) {
        this.planRepo = planRepo;
        this.subRepo = subRepo;
        this.invoiceRepo = invoiceRepo;
        this.creditRepo = creditRepo;
        this.calculator = calculator;
    }

    @Transactional
    public Subscription create(String customerId, String planId, LocalDate today) {
        Plan plan = planRepo.findById(planId)
                .orElseThrow(() -> new BillingException("Unknown plan: " + planId));
        LocalDate end = today.plusMonths(1);
        Subscription sub = subRepo.save(new Subscription(customerId, planId, today, end));

        Invoice invoice = new Invoice(sub.getId(), customerId, plan.monthlyPrice().currencyCode());
        invoice.addLineItem(InvoiceLineItem.charge(
                "Subscription to " + plan.getName(),
                plan.monthlyPrice(),
                today, end, plan.getId()));
        applyCreditAndPersist(invoice, customerId);
        return sub;
    }

    /**
     * Change the plan on an active subscription.
     *
     * @return the invoice issued by the change (may be empty for END_OF_CYCLE).
     */
    @Transactional
    public Invoice changePlan(Long subscriptionId, String newPlanId,
                              ChangeStrategy strategy, LocalDate today) {
        Subscription sub = activeSub(subscriptionId);
        Plan oldPlan = planRepo.findById(sub.getPlanId())
                .orElseThrow(() -> new BillingException("Unknown current plan"));
        Plan newPlan = planRepo.findById(newPlanId)
                .orElseThrow(() -> new BillingException("Unknown new plan: " + newPlanId));

        if (oldPlan.getId().equals(newPlan.getId())) {
            throw new BillingException("New plan is the same as the current plan");
        }
        if (!oldPlan.monthlyPrice().currencyCode().equals(newPlan.monthlyPrice().currencyCode())) {
            throw new BillingException("Cross-currency plan changes are not supported in this POC");
        }

        return switch (strategy) {
            case IMMEDIATE_PRORATE -> applyImmediate(sub, oldPlan, newPlan, today);
            case END_OF_CYCLE -> applyEndOfCycle(sub, newPlan);
        };
    }

    /**
     * Cancel mid-cycle: credit unused days to the customer balance. The cancellation
     * invoice nets to zero — the credit line is offset by a banking line, identical to
     * the downgrade case.
     */
    @Transactional
    public Invoice cancel(Long subscriptionId, LocalDate today) {
        Subscription sub = activeSub(subscriptionId);
        Plan plan = planRepo.findById(sub.getPlanId())
                .orElseThrow(() -> new BillingException("Unknown current plan"));

        Invoice invoice = new Invoice(sub.getId(), sub.getCustomerId(),
                plan.monthlyPrice().currencyCode());
        InvoiceLineItem creditLine = calculator.prorateCancellation(
                plan, sub.getPeriodStart(), sub.getPeriodEnd(), today);
        invoice.addLineItem(creditLine);

        sub.cancel();
        subRepo.save(sub);
        return applyCreditAndPersist(invoice, sub.getCustomerId());
    }

    private Invoice applyImmediate(Subscription sub, Plan oldPlan, Plan newPlan, LocalDate today) {
        LocalDate effective = today.isBefore(sub.getPeriodStart()) ? sub.getPeriodStart() : today;
        Invoice invoice = new Invoice(sub.getId(), sub.getCustomerId(),
                newPlan.monthlyPrice().currencyCode());

        List<InvoiceLineItem> items = calculator.prorate(
                oldPlan, newPlan, sub.getPeriodStart(), sub.getPeriodEnd(), effective);
        items.forEach(invoice::addLineItem);

        sub.switchPlanImmediately(newPlan.getId(), effective, sub.getPeriodEnd());
        subRepo.save(sub);

        return applyCreditAndPersist(invoice, sub.getCustomerId());
    }

    private Invoice applyEndOfCycle(Subscription sub, Plan newPlan) {
        sub.schedulePlanChange(newPlan.getId());
        subRepo.save(sub);
        // Empty invoice intentionally: no money moves at scheduling time.
        Invoice invoice = new Invoice(sub.getId(), sub.getCustomerId(),
                newPlan.monthlyPrice().currencyCode());
        invoice.addLineItem(InvoiceLineItem.charge(
                "Plan change scheduled to take effect on " + sub.getPeriodEnd() +
                        " (no charge today)",
                Money.zero(newPlan.monthlyPrice().currencyCode()),
                sub.getPeriodEnd(), sub.getPeriodEnd().plusMonths(1), newPlan.getId()));
        return invoiceRepo.save(invoice);
    }

    /**
     * Apply credit balance against the invoice net total (if positive). If the net is
     * negative (downgrade where credit exceeds new charge), bank the leftover as credit.
     * Always invoice — never directly charge.
     */
    private Invoice applyCreditAndPersist(Invoice invoice, String customerId) {
        Money total = invoice.total();
        String currency = invoice.getCurrency();

        if (total.isPositive()) {
            CustomerCredit credit = creditRepo.findById(customerId).orElse(null);
            if (credit != null && credit.balance().isPositive()) {
                Money applied = credit.consumeUpTo(total);
                if (applied.isPositive()) {
                    invoice.addLineItem(InvoiceLineItem.credit(
                            "Applied credit balance", applied,
                            null, null, null));
                    creditRepo.save(credit);
                }
            }
        } else if (total.isNegative()) {
            Money surplus = total.negate();
            grantCredit(customerId, surplus, currency);
            invoice.addLineItem(InvoiceLineItem.charge(
                    "Credited to customer balance (no cash refund)",
                    surplus,
                    null, null, null));
        }
        invoice.close();
        return invoiceRepo.save(invoice);
    }

    private void grantCredit(String customerId, Money amount, String currency) {
        CustomerCredit credit = creditRepo.findById(customerId)
                .orElseGet(() -> new CustomerCredit(customerId, currency));
        credit.add(amount);
        creditRepo.save(credit);
    }

    private Subscription activeSub(Long id) {
        Subscription sub = subRepo.findById(id)
                .orElseThrow(() -> new BillingException("Unknown subscription: " + id));
        if (sub.getStatus() != Subscription.SubscriptionStatus.ACTIVE) {
            throw new BillingException("Subscription " + id + " is not active");
        }
        return sub;
    }

    public List<Invoice> invoicesFor(Long subscriptionId) {
        List<Invoice> all = new ArrayList<>(invoiceRepo.findAll());
        all.removeIf(i -> !i.getSubscriptionId().equals(subscriptionId));
        return all;
    }

    public Money creditBalance(String customerId) {
        return creditRepo.findById(customerId)
                .map(CustomerCredit::balance)
                .orElse(Money.zero("USD"));
    }

    public List<Plan> plansForListing() {
        return planRepo.findAll();
    }

    public List<Subscription> allSubscriptions() {
        return subRepo.findAll();
    }
}
