package vn.com.ipas.poc.fulfillment;

import vn.com.ipas.poc.common.Banner;
import vn.com.ipas.poc.fulfillment.Domain.*;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class FulfillmentRoutingPoc {

    private static final NumberFormat VND = NumberFormat.getInstance(new Locale("vi", "VN"));

    public static void main(String[] args) {
        Banner.section("POC 5 — Multi-warehouse fulfillment routing");

        List<Warehouse> warehouses = List.of(
                new Warehouse("HN-01", "North",
                        Map.of("SKU-A", 3, "SKU-B", 1, "SKU-C", 0),
                        20_000.0, 24),
                new Warehouse("DN-01", "Central",
                        Map.of("SKU-A", 1, "SKU-B", 1, "SKU-C", 5),
                        25_000.0, 36),
                new Warehouse("HCM-01", "South",
                        Map.of("SKU-A", 5, "SKU-B", 3, "SKU-C", 2),
                        18_000.0, 24),
                new Warehouse("HCM-02", "South",
                        Map.of("SKU-A", 0, "SKU-B", 2, "SKU-C", 4),
                        22_000.0, 48)
        );

        Order order = new Order("ORD-1001", "South", List.of(
                new OrderLine("SKU-A", 2),
                new OrderLine("SKU-B", 1),
                new OrderLine("SKU-C", 1)
        ));

        System.out.println("Order ORD-1001 (customer in South region):");
        order.lines().forEach(l -> System.out.printf("  %s x %d%n", l.sku(), l.qty()));

        printWarehouses(warehouses);

        Banner.sub("Greedy router (cheapest per line)");
        var greedyPlan = new GreedyRouter().route(order, warehouses);
        printPlan(greedyPlan);

        Banner.sub("Optimal router (cost + shipment-count penalty)");
        var optimalPlan = new OptimalRouter().route(order, warehouses);
        printPlan(optimalPlan);

        Banner.sub("Comparison");
        System.out.printf("Greedy:  %d shipments, %s VND, max SLA %d h%n",
                greedyPlan.shipmentCount(), VND.format((long) greedyPlan.totalCost()), greedyPlan.maxSla());
        System.out.printf("Optimal: %d shipments, %s VND, max SLA %d h%n",
                optimalPlan.shipmentCount(), VND.format((long) optimalPlan.totalCost()), optimalPlan.maxSla());
        long saving = (long) (greedyPlan.totalCost() - optimalPlan.totalCost());
        System.out.printf("Cost delta: %s VND (negative = optimal pays more for fewer parcels)%n",
                VND.format(saving));

        Banner.sub("Takeaways");
        System.out.println("- Greedy is fast and easy to explain, but happily creates 4 shipments to save ₫5k.");
        System.out.println("- Optimal uses a weighted scalar (cost + split penalty) so the score reflects the real policy.");
        System.out.println("- For 50+ warehouses, cluster by region first and run optimisation within clusters.");
        System.out.println("- Partial cancellation: re-run routing on the remaining lines — plans must be re-computable.");
    }

    private static void printWarehouses(List<Warehouse> warehouses) {
        System.out.println();
        System.out.println("Warehouses (inventory by SKU):");
        System.out.printf("  %-7s %-7s %-7s %-7s %-7s %-12s %-7s%n",
                "id", "region", "SKU-A", "SKU-B", "SKU-C", "₫/box", "SLA(h)");
        for (var w : warehouses) {
            System.out.printf("  %-7s %-7s %-7d %-7d %-7d %-12s %-7d%n",
                    w.id(), w.region(),
                    w.inventory().getOrDefault("SKU-A", 0),
                    w.inventory().getOrDefault("SKU-B", 0),
                    w.inventory().getOrDefault("SKU-C", 0),
                    VND.format((long) w.shipCostPerBox()),
                    w.slaHours());
        }
    }

    private static void printPlan(Plan plan) {
        for (Shipment s : plan.shipments()) {
            System.out.printf("  ship from %-7s: %s (₫%s, SLA %dh)%n",
                    s.warehouseId(), s.assignments(),
                    VND.format((long) s.cost()), s.slaHours());
        }
        System.out.printf("  TOTAL: %d shipments, %s VND%n",
                plan.shipmentCount(), VND.format((long) plan.totalCost()));
    }
}
