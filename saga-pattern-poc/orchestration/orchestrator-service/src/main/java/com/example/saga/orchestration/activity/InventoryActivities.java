package com.example.saga.orchestration.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface InventoryActivities {

    @ActivityMethod
    String reserve(String orderId, String productId, int quantity);

    @ActivityMethod
    void release(String reservationId);
}
