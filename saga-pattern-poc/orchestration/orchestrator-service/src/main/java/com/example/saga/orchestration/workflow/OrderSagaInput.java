package com.example.saga.orchestration.workflow;

import java.math.BigDecimal;

public record OrderSagaInput(
        String orderId,
        String customerId,
        String productId,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal totalAmount,
        String shippingAddress
) {
}
