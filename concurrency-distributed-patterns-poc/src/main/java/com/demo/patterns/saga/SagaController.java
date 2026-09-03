package com.demo.patterns.saga;

import jakarta.annotation.PostConstruct;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/demo/saga")
public class SagaController {

    private final OrderSagaOrchestrator saga;
    private final PaymentService payments;
    private final InventoryService inventory;

    public SagaController(OrderSagaOrchestrator saga,
                          PaymentService payments,
                          InventoryService inventory) {
        this.saga = saga;
        this.payments = payments;
        this.inventory = inventory;
    }

    @PostConstruct
    void seed() {
        payments.seed("alice", 1000);
        payments.seed("bob", 50);
        inventory.seed("SKU-A", 5);
        inventory.seed("SKU-B", 0);
    }

    /**
     * Run the full order saga. Use {@code failAt} to inject a failure at one
     * of: {@code payment | inventory | shipping | confirm} and watch the
     * compensations unwind in reverse order.
     */
    @PostMapping("/order")
    public OrderSagaOrchestrator.SagaResult placeOrder(
            @RequestParam(defaultValue = "alice") String customer,
            @RequestParam(defaultValue = "100") long amount,
            @RequestParam(defaultValue = "SKU-A") String sku,
            @RequestParam(defaultValue = "1") int quantity,
            @RequestParam(defaultValue = "1 Infinite Loop") String address,
            @RequestParam(required = false) String failAt) {

        return saga.placeOrder(new OrderSagaOrchestrator.SagaRequest(
                customer, amount, sku, quantity, address, failAt));
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        return Map.of(
                "alice_balance", payments.balanceOf("alice"),
                "bob_balance", payments.balanceOf("bob"),
                "SKU_A_stock", inventory.available("SKU-A"),
                "SKU_B_stock", inventory.available("SKU-B")
        );
    }
}
