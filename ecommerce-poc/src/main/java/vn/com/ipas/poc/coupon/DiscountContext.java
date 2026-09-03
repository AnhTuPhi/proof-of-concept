package vn.com.ipas.poc.coupon;

import java.util.ArrayList;
import java.util.List;

/**
 * Running state of a discount application. Each coupon reads
 * `currentSubtotal` / `currentShipping`, then mutates them through
 * apply*() helpers. The audit trail is what you'd show the user
 * ("10% off applied: −₫50,000") and what fraud investigates later.
 */
public final class DiscountContext {
    private final Cart cart;
    private long currentSubtotal;
    private long currentShipping;
    private final List<AuditEntry> audit = new ArrayList<>();

    public DiscountContext(Cart cart) {
        this.cart = cart;
        this.currentSubtotal = cart.subtotal();
        this.currentShipping = cart.shippingFee();
    }

    public Cart cart() { return cart; }
    public long currentSubtotal() { return currentSubtotal; }
    public long currentShipping() { return currentShipping; }
    public List<AuditEntry> audit() { return List.copyOf(audit); }

    void applyToSubtotal(long discount) {
        long capped = Math.min(discount, currentSubtotal);
        currentSubtotal -= capped;
    }

    void applyToShipping(long discount) {
        long capped = Math.min(discount, currentShipping);
        currentShipping -= capped;
    }

    void log(Coupon c, long discount) {
        audit.add(new AuditEntry(c.code(), c.stage(), discount));
    }

    public long finalTotal() {
        return currentSubtotal + currentShipping;
    }

    public record AuditEntry(String code, Coupon.Stage stage, long discount) {}
}
