package com.example.saga.orchestration.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface ShippingActivities {

    @ActivityMethod
    ShipmentResult schedule(String orderId, String address);

    @ActivityMethod
    void cancel(String shipmentId);
}
