package com.example.fintech.refund;

import java.math.BigDecimal;

public record RefundRequest(String paymentId, BigDecimal requestedAmount, boolean fullRefund) {}
