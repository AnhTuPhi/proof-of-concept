package com.demo.patterns.outbox;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/demo/outbox")
public class OutboxController {

    private final OrderService orderService;
    private final OutboxEventRepository outbox;
    private final InMemoryEventBus bus;
    private final OutboxRelay relay;

    public OutboxController(OrderService orderService,
                            OutboxEventRepository outbox,
                            InMemoryEventBus bus,
                            OutboxRelay relay) {
        this.orderService = orderService;
        this.outbox = outbox;
        this.bus = bus;
        this.relay = relay;
    }

    @PostMapping("/orders")
    public Map<String, Object> place(@RequestParam String customer,
                                     @RequestParam String product,
                                     @RequestParam(defaultValue = "1") int quantity) {
        OrderEntity o = orderService.placeOrder(customer, product, quantity);
        Map<String, Object> m = new HashMap<>();
        m.put("orderId", o.getId());
        m.put("customer", o.getCustomer());
        m.put("product", o.getProduct());
        m.put("quantity", o.getQuantity());
        m.put("note", "outbox event written in same transaction — relay will pick it up");
        return m;
    }

    @GetMapping("/pending")
    public Map<String, Object> pending() {
        List<OutboxEvent> recent = outbox.findUnprocessed(PageRequest.of(0, 50));
        return Map.of(
                "pendingCount", outbox.countByProcessedAtIsNull(),
                "items", recent.stream().map(OutboxController::view).toList()
        );
    }

    @GetMapping("/published")
    public List<InMemoryEventBus.Delivered> published() {
        return bus.recent();
    }

    /** Pause/resume the relay — useful for seeing the outbox accumulate. */
    @PostMapping("/relay/pause")
    public Map<String, Object> pause(@RequestParam boolean paused) {
        relay.setPaused(paused);
        return Map.of("paused", relay.isPaused());
    }

    private static Map<String, Object> view(OutboxEvent e) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", e.getId());
        m.put("type", e.getEventType());
        m.put("aggregateId", e.getAggregateId());
        m.put("payload", e.getPayload());
        m.put("attempts", e.getAttempts());
        m.put("createdAt", e.getCreatedAt());
        return m;
    }
}
