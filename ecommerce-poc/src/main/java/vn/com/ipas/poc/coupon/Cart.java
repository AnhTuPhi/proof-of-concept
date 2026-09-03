package vn.com.ipas.poc.coupon;

import java.util.List;

public record Cart(List<LineItem> items, long shippingFee) {
    public record LineItem(String sku, String category, long unitPrice, int qty) {
        public long lineTotal() { return unitPrice * qty; }
    }

    public long subtotal() {
        return items.stream().mapToLong(LineItem::lineTotal).sum();
    }

    public long categorySubtotal(String category) {
        return items.stream().filter(i -> i.category().equals(category)).mapToLong(LineItem::lineTotal).sum();
    }
}
