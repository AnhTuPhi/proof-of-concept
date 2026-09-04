package com.example.saga.choreography.order.api;

import com.example.saga.choreography.order.service.OrderService;
import com.example.saga.common.dto.OrderRequest;
import com.example.saga.common.dto.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Choreography saga REST API")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @Operation(summary = "Place an order and kick off the choreography saga")
    public ResponseEntity<OrderResponse> create(@RequestBody @Valid OrderRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(orderService.createOrder(request));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Fetch order by id")
    public ResponseEntity<OrderResponse> get(@PathVariable String orderId) {
        return ResponseEntity.ok(orderService.getOrder(orderId));
    }

    @GetMapping("/by-saga/{sagaId}")
    @Operation(summary = "Fetch order by saga id")
    public ResponseEntity<OrderResponse> getBySaga(@PathVariable String sagaId) {
        return ResponseEntity.ok(orderService.getOrderBySaga(sagaId));
    }
}
