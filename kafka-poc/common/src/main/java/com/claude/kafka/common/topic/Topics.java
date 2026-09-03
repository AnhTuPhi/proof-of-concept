package com.claude.kafka.common.topic;

/**
 * Centralized topic names so every module agrees.
 * Naming convention: {@code <domain>.<aggregate>.<event-type>.<version>}
 * <p>
 * Production tip: never let consumers/producers hard-code raw strings.
 * One typo and you've created a ghost topic that auto-creation will happily
 * spin up with default settings (wrong partition count, wrong retention).
 */
public final class Topics {

    private Topics() {}

    // ---- Orders domain ----
    public static final String ORDERS_PLACED       = "orders.placed.v1";
    public static final String ORDERS_PLACED_RETRY = "orders.placed.v1.retry";
    public static final String ORDERS_PLACED_DLQ   = "orders.placed.v1.dlq";

    public static final String ORDERS_PAID         = "orders.paid.v1";
    public static final String ORDERS_SHIPPED      = "orders.shipped.v1";
    public static final String ORDERS_CANCELLED    = "orders.cancelled.v1";

    // ---- Payments (Saga) ----
    public static final String PAYMENTS_REQUESTED  = "payments.requested.v1";
    public static final String PAYMENTS_COMPLETED  = "payments.completed.v1";
    public static final String PAYMENTS_FAILED     = "payments.failed.v1";

    // ---- Inventory (Saga) ----
    public static final String INVENTORY_RESERVE_REQUESTED = "inventory.reserve.requested.v1";
    public static final String INVENTORY_RESERVED          = "inventory.reserved.v1";
    public static final String INVENTORY_RESERVE_FAILED    = "inventory.reserve.failed.v1";

    // ---- Shipping ----
    public static final String SHIPPING_REQUESTED  = "shipping.requested.v1";
    public static final String SHIPPING_COMPLETED  = "shipping.completed.v1";

    // ---- CDC ----
    public static final String CDC_ORDERS          = "cdc.appuser.orders";
    public static final String CDC_OUTBOX          = "cdc.appuser.outbox";

    // ---- Streams demo topics ----
    public static final String CLICKSTREAM         = "clickstream.events.v1";
    public static final String CLICKSTREAM_WINDOW  = "clickstream.windowed.v1";
    public static final String USERS_TABLE         = "users.profile.v1";
    public static final String ENRICHED_CLICKS     = "clickstream.enriched.v1";
}
