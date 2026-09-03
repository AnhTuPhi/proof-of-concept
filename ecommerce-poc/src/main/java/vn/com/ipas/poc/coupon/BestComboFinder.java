package vn.com.ipas.poc.coupon;

import java.util.ArrayList;
import java.util.List;

/**
 * "Best deal" auto-selection. Brute-forces all 2^N subsets of the user's
 * eligible coupons, drops non-stackable subsets, picks the one with the
 * lowest final total. For typical wallets (≤20 coupons) this is sub-millisecond.
 * If you ever ship a user with hundreds of eligible coupons, switch to a
 * stage-stratified greedy with a beam — but ship the brute force first and
 * measure before optimising.
 */
public final class BestComboFinder {

    private final CouponEngine engine = new CouponEngine();

    public Result findBest(Cart cart, List<Coupon> wallet) {
        if (wallet.size() > 25) {
            throw new IllegalArgumentException(
                    "Brute-force best-combo is bounded at 25 coupons; got " + wallet.size());
        }
        long best = engine.apply(cart, List.of()).finalTotal();
        List<Coupon> bestCombo = List.of();
        int n = wallet.size();

        for (int mask = 1; mask < (1 << n); mask++) {
            List<Coupon> subset = new ArrayList<>(Integer.bitCount(mask));
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) subset.add(wallet.get(i));
            }
            if (!engine.isStackable(subset)) continue;
            long total = engine.apply(cart, subset).finalTotal();
            if (total < best) {
                best = total;
                bestCombo = subset;
            }
        }
        return new Result(bestCombo, best);
    }

    public record Result(List<Coupon> combo, long finalTotal) {}
}
