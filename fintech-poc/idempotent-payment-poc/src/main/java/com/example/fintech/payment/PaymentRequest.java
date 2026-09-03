package com.example.fintech.payment;

import com.example.fintech.common.Currency;

import java.math.BigDecimal;

public record PaymentRequest(BigDecimal amount, Currency currency, String customerId) {}
