package vn.com.ipas.poc.coupon;

public record PercentCoupon(String code, int percent, long maxDiscount) implements Coupon {
    public PercentCoupon {
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("percent 0..100");
    }
    @Override public Stage stage() { return Stage.PERCENT_ON_SUBTOTAL; }
    @Override public long discount(DiscountContext ctx) {
        long raw = ctx.cart().subtotal() * percent / 100;
        return Math.min(raw, maxDiscount);
    }
    @Override public boolean stackableWith(Coupon other) {
        // Only one global percent coupon at a time
        return !(other instanceof PercentCoupon);
    }
}
