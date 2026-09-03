package vn.com.ipas.poc.coupon;

public record FixedAmountCoupon(String code, long amount, long minSubtotal) implements Coupon {
    @Override public Stage stage() { return Stage.FIXED_AMOUNT; }
    @Override public long discount(DiscountContext ctx) {
        if (ctx.currentSubtotal() < minSubtotal) return 0;
        return Math.min(amount, ctx.currentSubtotal());
    }
    @Override public boolean stackableWith(Coupon other) {
        return !(other instanceof FixedAmountCoupon);
    }
}
