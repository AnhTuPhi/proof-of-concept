package com.example.billing.proration;

import com.example.billing.common.Money;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Pure proration math. Stripe-style: a mid-cycle change produces two line items —
 * a credit for the unused portion of the OLD plan, and a charge for the prorated
 * remainder of the NEW plan. Cycle length is the actual day count between
 * period_start and period_end (inclusive of start, exclusive of end).
 */
@Component
public class ProrationCalculator {

    /**
     * Calculate line items for an immediate plan change.
     *
     * @param oldPlan       plan currently in effect
     * @param newPlan       plan to switch to
     * @param periodStart   current cycle start
     * @param periodEnd     current cycle end (exclusive)
     * @param changeDate    date the switch takes effect (inclusive)
     * @return ordered list of line items (credit first, charge second)
     */
    public List<InvoiceLineItem> prorate(Plan oldPlan, Plan newPlan,
                                         LocalDate periodStart,
                                         LocalDate periodEnd,
                                         LocalDate changeDate) {
        if (!changeDate.isAfter(periodStart) && !changeDate.isEqual(periodStart)) {
            throw new IllegalArgumentException(
                    "changeDate " + changeDate + " must be on or after periodStart " + periodStart);
        }
        if (!changeDate.isBefore(periodEnd)) {
            throw new IllegalArgumentException(
                    "changeDate " + changeDate + " must be before periodEnd " + periodEnd);
        }
        long totalDays = ChronoUnit.DAYS.between(periodStart, periodEnd);
        long remainingDays = ChronoUnit.DAYS.between(changeDate, periodEnd);

        Money oldPrice = oldPlan.monthlyPrice();
        Money newPrice = newPlan.monthlyPrice();

        Money unusedCredit = oldPrice.prorate(remainingDays, totalDays);
        Money proratedCharge = newPrice.prorate(remainingDays, totalDays);

        String creditDesc = String.format(
                "Unused time on %s (%d of %d days)", oldPlan.getName(), remainingDays, totalDays);
        String chargeDesc = String.format(
                "Remaining time on %s (%d of %d days)", newPlan.getName(), remainingDays, totalDays);

        InvoiceLineItem credit = InvoiceLineItem.credit(
                creditDesc, unusedCredit, changeDate, periodEnd, oldPlan.getId());
        InvoiceLineItem charge = InvoiceLineItem.charge(
                chargeDesc, proratedCharge, changeDate, periodEnd, newPlan.getId());

        return List.of(credit, charge);
    }

    /**
     * Calculate the credit for a mid-cycle cancellation: full credit for unused days.
     * No new charge is issued; the credit goes to the customer's balance.
     */
    public InvoiceLineItem prorateCancellation(Plan currentPlan, LocalDate periodStart,
                                               LocalDate periodEnd, LocalDate cancelDate) {
        long totalDays = ChronoUnit.DAYS.between(periodStart, periodEnd);
        long remainingDays = ChronoUnit.DAYS.between(cancelDate, periodEnd);
        if (remainingDays <= 0) {
            return InvoiceLineItem.credit(
                    "Cancellation at period end — no unused time",
                    Money.zero(currentPlan.monthlyPrice().currencyCode()),
                    cancelDate, periodEnd, currentPlan.getId());
        }
        Money credit = currentPlan.monthlyPrice().prorate(remainingDays, totalDays);
        String desc = String.format(
                "Cancellation credit: %d unused days on %s", remainingDays, currentPlan.getName());
        return InvoiceLineItem.credit(desc, credit, cancelDate, periodEnd, currentPlan.getId());
    }
}
