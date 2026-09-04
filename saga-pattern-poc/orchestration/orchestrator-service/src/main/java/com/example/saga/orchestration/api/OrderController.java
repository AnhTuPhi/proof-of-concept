package com.example.saga.orchestration.api;

import com.example.saga.common.dto.OrderRequest;
import com.example.saga.common.dto.OrderResponse;
import com.example.saga.orchestration.service.OrderOrchestrationService;
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
@Tag(name = "Orders", description = "Orchestration saga REST API")
public class OrderController {

    private final OrderOrchestrationService service;

    @PostMapping
    @Operation(summary = "Place an order and start the Temporal workflow")
    public ResponseEntity<OrderResponse> create(@RequestBody @Valid OrderRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.placeOrder(request));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Fetch order. Status is reconciled from the Temporal workflow on each read.")
    public ResponseEntity<OrderResponse> get(@PathVariable String orderId) {
        return ResponseEntity.ok(service.getOrder(orderId));
    }
}
