package vn.com.ipas.poc.fulfillment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Domain {
    private Domain() {}

    public record Warehouse(
            String id,
            String region,
            Map<String, Integer> inventory,
            double shipCostPerBox,
            int slaHours
    ) {}

    public record OrderLine(String sku, int qty) {}

    public record Order(String id, String customerRegion, List<OrderLine> lines) {
        public int totalUnits() { return lines.stream().mapToInt(OrderLine::qty).sum(); }
    }

    /**
     * One physical shipment from a warehouse. assignments are SKU -> qty.
     * Cost is a function of the warehouse's per-box rate + a small penalty
     * for shipping out-of-region (real-world: customs, longer transit).
     */
    public record Shipment(String warehouseId, Map<String, Integer> assignments, double cost, int slaHours) {}

    public record Plan(List<Shipment> shipments) {
        public double totalCost() { return shipments.stream().mapToDouble(Shipment::cost).sum(); }
        public int maxSla() { return shipments.stream().mapToInt(Shipment::slaHours).max().orElse(0); }
        public int shipmentCount() { return shipments.size(); }
    }

    /** Mutable inventory used by routers — copy of the warehouse map. */
    public static Map<String, Map<String, Integer>> mutableSnapshot(List<Warehouse> warehouses) {
        Map<String, Map<String, Integer>> snap = new LinkedHashMap<>();
        for (Warehouse w : warehouses) {
            snap.put(w.id(), new LinkedHashMap<>(w.inventory()));
        }
        return snap;
    }
}
