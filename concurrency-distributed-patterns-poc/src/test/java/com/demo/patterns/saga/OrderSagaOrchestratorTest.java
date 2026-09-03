package com.demo.patterns.saga;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderSagaOrchestratorTest {

    private PaymentService payments;
    private InventoryService inventory;
    private OrderSagaOrchestrator saga;

    @BeforeEach
    void setUp() {
        payments = new PaymentService();
        inventory = new InventoryService();
        ShippingService shipping = new ShippingService();
        saga = new OrderSagaOrchestrator(payments, inventory, shipping);

        payments.seed("alice", 500);
        inventory.seed("SKU-A", 3);
    }

    @Test
    void happy_path_runs_all_steps() {
        var result = saga.placeOrder(new OrderSagaOrchestrator.SagaRequest(
                "alice", 100, "SKU-A", 1, "addr", null));
        assertTrue(result.success());
        assertEquals(4, result.steps().size());
        assertEquals(400, payments.balanceOf("alice"));
        assertEquals(2, inventory.available("SKU-A"));
    }

    @Test
    void inventory_failure_refunds_payment() {
        var result = saga.placeOrder(new OrderSagaOrchestrator.SagaRequest(
                "alice", 100, "SKU-A", 1, "addr", "inventory"));
        assertFalse(result.success());
        assertEquals(500, payments.balanceOf("alice"), "payment must be refunded");
        assertEquals(3, inventory.available("SKU-A"));
        assertTrue(result.steps().stream().anyMatch(s -> s.contains("COMPENSATED refundPayment")));
    }

    @Test
    void shipping_failure_compensates_in_reverse_order() {
        var result = saga.placeOrder(new OrderSagaOrchestrator.SagaRequest(
                "alice", 100, "SKU-A", 1, "addr", "shipping"));
        assertFalse(result.success());
        // Compensations should run inventory release first, then payment refund.
        int releaseIdx = -1, refundIdx = -1;
        for (int i = 0; i < result.steps().size(); i++) {
            String s = result.steps().get(i);
            if (s.contains("COMPENSATED releaseInventory")) releaseIdx = i;
            if (s.contains("COMPENSATED refundPayment")) refundIdx = i;
        }
        assertTrue(releaseIdx >= 0 && refundIdx >= 0);
        assertTrue(releaseIdx < refundIdx, "later step compensates first");
        assertEquals(500, payments.balanceOf("alice"));
        assertEquals(3, inventory.available("SKU-A"));
    }

    @Test
    void payment_failure_skips_compensation() {
        payments.seed("broke", 0);
        var result = saga.placeOrder(new OrderSagaOrchestrator.SagaRequest(
                "broke", 100, "SKU-A", 1, "addr", null));
        assertFalse(result.success());
        assertEquals(3, inventory.available("SKU-A"), "inventory must NOT be touched");
    }
}
