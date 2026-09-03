package com.example.cdc.order.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateOrderRequest(
        @NotBlank @Size(max = 64) String customerId,
        @NotBlank @Size(max = 64) String productSku,
        @Min(1) int quantity,
        @NotNull @DecimalMin(value = "0.0001") BigDecimal unitPrice
) {
}
