package vn.com.ipas.poc.coupon;

/**
 * Sealed coupon hierarchy. The application order is fixed by Stage so that
 * "10% off + ₫50k off" is unambiguous: percent (stage 10) always runs before
 * fixed-amount (stage 20), so the 10% applies to the original subtotal, not
 * the discounted one. Change the stage to change the policy globally.
 *
 * `stackableWith` is symmetric — both sides have to agree.
 */
public sealed interface Coupon
        permits PercentCoupon, FixedAmountCoupon, CategoryPercentCoupon, FreeShippingCoupon {

    String code();
    Stage stage();

    /**
     * @return discount, in the smallest currency unit, applied to ctx.
     *         Must NOT mutate ctx.
     */
    long discount(DiscountContext ctx);

    /**
     * Mutate ctx with the discount produced by this coupon.
     * Default = subtract from subtotal.
     */
    default void apply(DiscountContext ctx) {
        long d = discount(ctx);
        ctx.applyToSubtotal(d);
    }

    boolean stackableWith(Coupon other);

    enum Stage {
        /** Percentage on the original subtotal — runs first. */
        PERCENT_ON_SUBTOTAL(10),
        /** Per-category percent — runs after global percent. */
        CATEGORY_PERCENT(20),
        /** Flat amount off — runs on whatever's left. */
        FIXED_AMOUNT(30),
        /** Shipping — independent of subtotal stages. */
        SHIPPING(40);

        final int order;
        Stage(int order) { this.order = order; }
    }
}
