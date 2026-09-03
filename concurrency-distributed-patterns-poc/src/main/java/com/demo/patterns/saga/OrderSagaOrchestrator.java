package com.demo.patterns.saga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Orchestration-based saga. Each forward step pushes its compensation onto a
 * stack; on failure the stack is unwound in reverse. No 2PC, just local
 * transactions per service plus deliberate inverse actions.
 *
 * Steps:
 *   1. charge payment          → refund
 *   2. reserve inventory       → release
 *   3. create shipping label   → void
 *   4. confirm order           (terminal)
 */
@Service
public class OrderSagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaOrchestrator.class);

    private final PaymentService payments;
    private final InventoryService inventory;
    private final ShippingService shipping;

    public OrderSagaOrchestrator(PaymentService payments,
                                 InventoryService inventory,
                                 ShippingService shipping) {
        this.payments = payments;
        this.inventory = inventory;
        this.shipping = shipping;
    }

    public SagaResult placeOrder(SagaRequest req) {
        String orderId = "ord_" + UUID.randomUUID();
        Deque<Compensation> compensations = new ArrayDeque<>();
        List<String> log = new ArrayList<>();

        try {
            // Step 1: charge
            failIf(req.failAt(), "payment");
            String chargeId = payments.charge(req.customer(), req.amount());
            compensations.push(new Compensation("refundPayment", () -> payments.refund(req.customer(), chargeId)));
            log.add("CHARGED " + chargeId);

            // Step 2: reserve inventory
            failIf(req.failAt(), "inventory");
            String reservationId = inventory.reserve(req.sku(), req.quantity());
            compensations.push(new Compensation("releaseInventory", () -> inventory.release(reservationId)));
            log.add("RESERVED " + reservationId);

            // Step 3: shipping
            failIf(req.failAt(), "shipping");
            String labelId = shipping.createLabel(orderId, req.address());
            compensations.push(new Compensation("voidShipping", () -> shipping.voidLabel(labelId)));
            log.add("LABELED " + labelId);

            // Step 4: confirm
            failIf(req.failAt(), "confirm");
            log.add("CONFIRMED " + orderId);

            return SagaResult.success(orderId, log);
        } catch (RuntimeException e) {
            log.add("FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            List<String> compensated = compensate(compensations);
            log.addAll(compensated);
            return SagaResult.failed(orderId, log, e.getMessage());
        }
    }

    private List<String> compensate(Deque<Compensation> stack) {
        List<String> entries = new ArrayList<>();
        while (!stack.isEmpty()) {
            Compensation c = stack.pop();
            try {
                c.action().run();
                entries.add("COMPENSATED " + c.name());
                log.info("Compensated step {}", c.name());
            } catch (RuntimeException e) {
                entries.add("COMPENSATION_FAILED " + c.name() + ": " + e.getMessage());
                log.error("Compensation {} failed", c.name(), e);
            }
        }
        return entries;
    }

    private static void failIf(String failAt, String step) {
        if (step.equalsIgnoreCase(failAt)) {
            throw new RuntimeException("Injected failure at step '" + step + "'");
        }
    }

    private record Compensation(String name, Runnable action) {}

    public record SagaRequest(String customer, long amount, String sku, int quantity,
                              String address, String failAt) {}

    public record SagaResult(String orderId, boolean success, List<String> steps, String error) {
        public static SagaResult success(String id, List<String> log) {
            return new SagaResult(id, true, log, null);
        }
        public static SagaResult failed(String id, List<String> log, String error) {
            return new SagaResult(id, false, log, error);
        }
    }
}
