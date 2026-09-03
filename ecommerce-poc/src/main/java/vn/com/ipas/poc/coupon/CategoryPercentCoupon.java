package vn.com.ipas.poc.coupon;

public record CategoryPercentCoupon(String code, String category, int percent, long maxDiscount)
        implements Coupon {
    @Override public Stage stage() { return Stage.CATEGORY_PERCENT; }
    @Override public long discount(DiscountContext ctx) {
        long raw = ctx.cart().categorySubtotal(category) * percent / 100;
        return Math.min(raw, maxDiscount);
    }
    @Override public boolean stackableWith(Coupon other) {
        // Stack with anything except another category-percent for the same category
        return !(other instanceof CategoryPercentCoupon cpc && cpc.category.equals(category));
    }
}
