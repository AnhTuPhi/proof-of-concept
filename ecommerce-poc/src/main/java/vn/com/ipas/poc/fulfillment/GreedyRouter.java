package vn.com.ipas.poc.fulfillment;

import vn.com.ipas.poc.fulfillment.Domain.OrderLine;
import vn.com.ipas.poc.fulfillment.Domain.Plan;
import vn.com.ipas.poc.fulfillment.Domain.Shipment;
import vn.com.ipas.poc.fulfillment.Domain.Warehouse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Greedy: for each line, pick the warehouse that can fulfil the most
 * of it cheaply. Fast, easy to explain, often within 10–20% of optimal.
 * Falls back to splitting a single SKU across warehouses when no one
 * warehouse has enough on hand.
 */
public final class GreedyRouter {

    public Plan route(Domain.Order order, List<Warehouse> warehouses) {
        var inv = Domain.mutableSnapshot(warehouses);
        Map<String, Map<String, Integer>> picked = new LinkedHashMap<>();

        for (OrderLine line : order.lines()) {
            int remaining = line.qty();
            List<Warehouse> ranked = new ArrayList<>(warehouses);
            ranked.sort(Comparator.comparingDouble((Warehouse w) -> w.shipCostPerBox())
                    .thenComparing(w -> sameRegion(w, order) ? 0 : 1));

            for (Warehouse w : ranked) {
                if (remaining == 0) break;
                int avail = inv.get(w.id()).getOrDefault(line.sku(), 0);
                if (avail <= 0) continue;
                int take = Math.min(avail, remaining);
                inv.get(w.id()).merge(line.sku(), -take, Integer::sum);
                picked.computeIfAbsent(w.id(), k -> new LinkedHashMap<>())
                        .merge(line.sku(), take, Integer::sum);
                remaining -= take;
            }
            if (remaining > 0) {
                throw new IllegalStateException("Insufficient inventory for SKU " + line.sku());
            }
        }
        return toPlan(picked, warehouses, order);
    }

    static Plan toPlan(Map<String, Map<String, Integer>> picked,
                       List<Warehouse> warehouses,
                       Domain.Order order) {
        List<Shipment> shipments = new ArrayList<>();
        for (var entry : picked.entrySet()) {
            Warehouse w = warehouses.stream()
                    .filter(x -> x.id().equals(entry.getKey()))
                    .findFirst()
                    .orElseThrow();
            int units = entry.getValue().values().stream().mapToInt(Integer::intValue).sum();
            double regionPenalty = sameRegion(w, order) ? 1.0 : 1.4;
            double cost = w.shipCostPerBox() * Math.max(1, Math.ceil(units / 5.0)) * regionPenalty;
            shipments.add(new Shipment(w.id(), entry.getValue(), cost, w.slaHours()));
        }
        return new Plan(shipments);
    }

    static boolean sameRegion(Warehouse w, Domain.Order o) {
        return w.region().equals(o.customerRegion());
    }
}
