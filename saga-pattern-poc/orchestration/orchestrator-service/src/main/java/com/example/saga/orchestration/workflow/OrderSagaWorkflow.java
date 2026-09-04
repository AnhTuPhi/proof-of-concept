package com.example.saga.orchestration.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface OrderSagaWorkflow {

    String TASK_QUEUE = "ORDER_SAGA_TASK_QUEUE";

    @WorkflowMethod
    OrderSagaResult placeOrder(OrderSagaInput input);
}
