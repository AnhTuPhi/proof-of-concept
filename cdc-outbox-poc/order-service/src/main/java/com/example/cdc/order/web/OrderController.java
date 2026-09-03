package com.example.cdc.order.web;

import com.example.cdc.order.domain.Order;
import com.example.cdc.order.dto.CreateOrderRequest;
import com.example.cdc.order.dto.OrderResponse;
import com.example.cdc.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Order lifecycle operations")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @Operation(summary = "Create a new order. Writes order row + outbox event in one DB transaction.")
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        Order order = orderService.createOrder(
                request.customerId(),
                request.productSku(),
                request.quantity(),
                request.unitPrice()
        );
        URI location = UriComponentsBuilder.fromPath("/api/orders/{id}")
                .buildAndExpand(order.getId())
                .toUri();
        return ResponseEntity.created(location).body(OrderResponse.from(order));
    }

    @PostMapping("/{id}/pay")
    @Operation(summary = "Mark an order as paid. Emits OrderPaid event.")
    public OrderResponse pay(@PathVariable UUID id) {
        return OrderResponse.from(orderService.markPaid(id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order. Emits OrderCancelled event.")
    @ResponseStatus(HttpStatus.OK)
    public OrderResponse cancel(@PathVariable UUID id) {
        return OrderResponse.from(orderService.cancel(id));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch an order by id.")
    public OrderResponse get(@PathVariable UUID id) {
        return OrderResponse.from(orderService.findById(id));
    }
}
