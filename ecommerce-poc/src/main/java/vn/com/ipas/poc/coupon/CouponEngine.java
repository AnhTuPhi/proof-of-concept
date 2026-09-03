package vn.com.ipas.poc.coupon;

import java.util.Comparator;
import java.util.List;

public final class CouponEngine {

    /**
     * Apply a set of coupons in the deterministic stage order.
     * Returns a context with the running totals + audit trail.
     * Throws if any pair is not stackable — pre-validate with {@link #validateStackable}.
     */
    public DiscountContext apply(Cart cart, List<Coupon> coupons) {
        validateStackable(coupons);
        DiscountContext ctx = new DiscountContext(cart);
        List<Coupon> ordered = coupons.stream()
                .sorted(Comparator.comparingInt(c -> c.stage().order))
                .toList();
        for (Coupon c : ordered) {
            long before = ctx.finalTotal();
            c.apply(ctx);
            long after = ctx.finalTotal();
            ctx.log(c, before - after);
        }
        return ctx;
    }

    public boolean isStackable(List<Coupon> coupons) {
        for (int i = 0; i < coupons.size(); i++) {
            for (int j = i + 1; j < coupons.size(); j++) {
                if (!coupons.get(i).stackableWith(coupons.get(j))) return false;
            }
        }
        return true;
    }

    private void validateStackable(List<Coupon> coupons) {
        if (!isStackable(coupons)) {
            throw new IllegalStateException("Coupons not stackable: " +
                    coupons.stream().map(Coupon::code).toList());
        }
    }
}
