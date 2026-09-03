package vn.com.ipas.poc.coupon;

public record FreeShippingCoupon(String code, long minSubtotal) implements Coupon {
    @Override public Stage stage() { return Stage.SHIPPING; }
    @Override public long discount(DiscountContext ctx) {
        if (ctx.currentSubtotal() < minSubtotal) return 0;
        return ctx.currentShipping();
    }
    @Override public void apply(DiscountContext ctx) {
        ctx.applyToShipping(discount(ctx));
    }
    @Override public boolean stackableWith(Coupon other) {
        return !(other instanceof FreeShippingCoupon);
    }
}
