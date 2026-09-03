package vn.com.ipas.poc.coupon;

import vn.com.ipas.poc.common.Banner;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

public final class CouponEnginePoc {

    private static final NumberFormat VND = NumberFormat.getInstance(new Locale("vi", "VN"));

    public static void main(String[] args) {
        CouponEngine engine = new CouponEngine();

        // A typical Tiki/Shopee cart: 2 phones + 1 case + ₫30k shipping
        Cart cart = new Cart(List.of(
                new Cart.LineItem("IPHONE-15", "phones", 25_000_000L, 1),
                new Cart.LineItem("SAMSUNG-S24", "phones", 22_000_000L, 1),
                new Cart.LineItem("CASE-OEM", "accessories", 350_000L, 2)
        ), 30_000L);

        Banner.section("POC 4 — Coupon engine");
        System.out.printf("Cart subtotal: %s VND, shipping: %s VND, gross total: %s VND%n",
                VND.format(cart.subtotal()), VND.format(cart.shippingFee()),
                VND.format(cart.subtotal() + cart.shippingFee()));

        Coupon ten = new PercentCoupon("PERCENT10", 10, 500_000L);
        Coupon fifty = new FixedAmountCoupon("MINUS50K", 50_000L, 1_000_000L);
        Coupon ship = new FreeShippingCoupon("FREESHIP", 200_000L);
        Coupon phones15 = new CategoryPercentCoupon("PHONES15", "phones", 15, 2_000_000L);
        Coupon five = new PercentCoupon("PERCENT5", 5, 200_000L);

        scenario(engine, cart, "Single 10% off", List.of(ten));
        scenario(engine, cart, "Stack: 10% + ₫50k + free ship", List.of(ten, fifty, ship));
        scenario(engine, cart, "Conflict: two percentage coupons (engine should reject)", List.of(ten, five));
        scenario(engine, cart, "Targeted: 15% off phones only + free ship", List.of(phones15, ship));

        Banner.sub("Best-combo finder over the user's wallet");
        List<Coupon> wallet = List.of(ten, fifty, ship, phones15, five);
        System.out.println("Wallet: " + wallet.stream().map(Coupon::code).toList());
        BestComboFinder finder = new BestComboFinder();
        var best = finder.findBest(cart, wallet);
        System.out.println("Best combo:   " + best.combo().stream().map(Coupon::code).toList());
        System.out.println("Final total:  " + VND.format(best.finalTotal()) + " VND");
        System.out.println("You saved:    " + VND.format(cart.subtotal() + cart.shippingFee() - best.finalTotal()) + " VND");

        Banner.sub("Takeaways");
        System.out.println("- Stages (PERCENT_ON_SUBTOTAL → CATEGORY_PERCENT → FIXED_AMOUNT → SHIPPING) make the math deterministic.");
        System.out.println("- stackableWith is a symmetric pairwise check — easy to reason about, easy to unit-test.");
        System.out.println("- For typical wallets (≤20 coupons), brute-force subset search is sub-ms and exact.");
        System.out.println("- Audit trail is the source of truth for refunds, fraud reviews, and customer-support tickets.");
    }

    private static void scenario(CouponEngine engine, Cart cart, String title, List<Coupon> coupons) {
        Banner.sub(title);
        System.out.println("Applied: " + coupons.stream().map(Coupon::code).toList());
        try {
            DiscountContext ctx = engine.apply(cart, coupons);
            for (var entry : ctx.audit()) {
                if (entry.discount() == 0) continue;
                System.out.printf("  %-12s [%s] -%s VND%n",
                        entry.code(), entry.stage(), VND.format(entry.discount()));
            }
            System.out.printf("  -> subtotal: %s, shipping: %s, total: %s VND%n",
                    VND.format(ctx.currentSubtotal()),
                    VND.format(ctx.currentShipping()),
                    VND.format(ctx.finalTotal()));
        } catch (IllegalStateException e) {
            System.out.println("  REJECTED: " + e.getMessage());
        }
    }
}
