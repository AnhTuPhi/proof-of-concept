package com.example.saga.orchestration.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.math.BigDecimal;

@ActivityInterface
public interface PaymentActivities {

    @ActivityMethod
    String charge(String orderId, String customerId, BigDecimal amount);

    @ActivityMethod
    void refund(String paymentId);
}
