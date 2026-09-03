package com.example.billing.proration;

public enum ChangeStrategy {
    /**
     * Switch plan today. Issue a prorated credit for the unused portion of the old plan
     * and a prorated charge for the new plan covering the remainder of the cycle.
     */
    IMMEDIATE_PRORATE,

    /**
     * Keep the old plan active until period end; the new plan starts on the next renewal
     * with no proration math required.
     */
    END_OF_CYCLE
}
