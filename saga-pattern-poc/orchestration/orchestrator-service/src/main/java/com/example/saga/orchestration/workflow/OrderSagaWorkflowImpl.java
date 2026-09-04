package com.example.saga.orchestration.workflow;

import com.example.saga.orchestration.activity.InventoryActivities;
import com.example.saga.orchestration.activity.PaymentActivities;
import com.example.saga.orchestration.activity.ShipmentResult;
import com.example.saga.orchestration.activity.ShippingActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;

/**
 * Orchestrated order saga driven by a Temporal workflow.
 *
 * <p>Each forward step is paired with a compensation registered on the {@link Saga} primitive.
 * If any step throws after the activity's retry budget is exhausted, Temporal calls
 * {@link Saga#compensate()} which runs the registered compensations in reverse order — same
 * net effect as the choreography compensation chain, but expressed as straight-line code
 * that you can read top to bottom.
 *
 * <p>All activity retries, timeouts and durable state live inside Temporal: a worker crash
 * mid-workflow simply has another worker pick up where it left off after the workflow task
 * is re-scheduled.
 */
@WorkflowImpl(taskQueues = OrderSagaWorkflow.TASK_QUEUE)
public class OrderSagaWorkflowImpl implements OrderSagaWorkflow {

    private static final Logger log = Workflow.getLogger(OrderSagaWorkflowImpl.class);

    private static final ActivityOptions DEFAULT_ACTIVITY_OPTIONS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setScheduleToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setMaximumInterval(Duration.ofSeconds(30))
                    .setBackoffCoefficient(2.0)
                    .setMaximumAttempts(5)
                    .setDoNotRetry(
                            "com.example.saga.orchestration.exception.NonRetryablePaymentException",
                            "com.example.saga.orchestration.exception.NonRetryableInventoryException",
                            "com.example.saga.orchestration.exception.NonRetryableShippingException")
                    .build())
            .build();

    private final PaymentActivities payment = Workflow.newActivityStub(PaymentActivities.class, DEFAULT_ACTIVITY_OPTIONS);
    private final InventoryActivities inventory = Workflow.newActivityStub(InventoryActivities.class, DEFAULT_ACTIVITY_OPTIONS);
    private final ShippingActivities shipping = Workflow.newActivityStub(ShippingActivities.class, DEFAULT_ACTIVITY_OPTIONS);

    @Override
    public OrderSagaResult placeOrder(OrderSagaInput input) {
        log.info("Starting saga for order {}", input.orderId());

        Saga.Options options = new Saga.Options.Builder()
                .setParallelCompensation(false) // run compensations in reverse, one at a time
                .setContinueWithError(true)     // keep compensating even if one comp fails
                .build();
        Saga saga = new Saga(options);

        String paymentId = null;
        String reservationId = null;
        String shipmentId = null;
        String trackingNumber = null;

        try {
            paymentId = payment.charge(input.orderId(), input.customerId(), input.totalAmount());
            String capturedPaymentId = paymentId;
            saga.addCompensation(() -> payment.refund(capturedPaymentId));

            reservationId = inventory.reserve(input.orderId(), input.productId(), input.quantity());
            String capturedReservationId = reservationId;
            saga.addCompensation(() -> inventory.release(capturedReservationId));

            ShipmentResult shipment = shipping.schedule(input.orderId(), input.shippingAddress());
            shipmentId = shipment.shipmentId();
            trackingNumber = shipment.trackingNumber();
            String capturedShipmentId = shipmentId;
            saga.addCompensation(() -> shipping.cancel(capturedShipmentId));

            log.info("Saga {} completed successfully", input.orderId());
            return new OrderSagaResult(
                    input.orderId(),
                    "COMPLETED",
                    paymentId,
                    reservationId,
                    shipmentId,
                    trackingNumber,
                    null);
        } catch (ActivityFailure failure) {
            log.warn("Saga {} failed, running compensation. Cause: {}",
                    input.orderId(), failure.getCause() != null ? failure.getCause().getMessage() : failure.getMessage());
            saga.compensate();
            String reason = failure.getCause() != null ? failure.getCause().getMessage() : failure.getMessage();
            return new OrderSagaResult(
                    input.orderId(),
                    "COMPENSATED",
                    paymentId,
                    reservationId,
                    shipmentId,
                    trackingNumber,
                    reason);
        }
    }
}
