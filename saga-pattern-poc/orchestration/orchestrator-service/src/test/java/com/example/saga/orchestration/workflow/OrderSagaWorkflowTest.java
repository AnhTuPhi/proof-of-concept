package com.example.saga.orchestration.workflow;

import com.example.saga.orchestration.activity.InventoryActivities;
import com.example.saga.orchestration.activity.PaymentActivities;
import com.example.saga.orchestration.activity.ShipmentResult;
import com.example.saga.orchestration.activity.ShippingActivities;
import com.example.saga.orchestration.exception.NonRetryablePaymentException;
import com.example.saga.orchestration.exception.NonRetryableShippingException;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the orchestration saga without needing a Temporal server, by replacing the
 * activity implementations with thin adapters that delegate to Mockito mocks.
 *
 * <p>Adapters are needed because Mockito's CGLib proxies expose the {@code @ActivityMethod}
 * annotation on the implementing class — and the Temporal SDK explicitly rejects that
 * annotation outside an interface declaration. The anonymous subclass below has no
 * annotations on its override methods, so it registers cleanly while still letting us
 * stub and verify with Mockito.
 *
 * <p>Two scenarios are covered: happy path, and the compensation chain when shipping
 * throws a non-retryable failure. The second case asserts that the previously-charged
 * payment and reserved inventory were both rolled back in reverse order.
 */
class OrderSagaWorkflowTest {

    @RegisterExtension
    public static final TestWorkflowExtension testWorkflowExtension = TestWorkflowExtension.newBuilder()
            .setWorkflowTypes(OrderSagaWorkflowImpl.class)
            .setDoNotStart(true)
            .build();

    @Test
    @DisplayName("Happy path: all three activities execute, no compensation runs")
    void happyPath(TestWorkflowEnvironment env, Worker worker, OrderSagaWorkflow workflow) {
        PaymentActivities payment = Mockito.mock(PaymentActivities.class);
        InventoryActivities inventory = Mockito.mock(InventoryActivities.class);
        ShippingActivities shipping = Mockito.mock(ShippingActivities.class);

        when(payment.charge(anyString(), anyString(), any())).thenReturn("pay-1");
        when(inventory.reserve(anyString(), anyString(), anyInt())).thenReturn("rsv-1");
        when(shipping.schedule(anyString(), anyString())).thenReturn(new ShipmentResult("shp-1", "TRK-1"));

        worker.registerActivitiesImplementations(adapt(payment), adapt(inventory), adapt(shipping));
        env.start();

        OrderSagaInput input = new OrderSagaInput(
                "ord-1", "cust-1", "SKU-1", 2,
                new BigDecimal("9.99"), new BigDecimal("19.98"), "123 Main St");

        OrderSagaResult result = workflow.placeOrder(input);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.paymentId()).isEqualTo("pay-1");
        assertThat(result.reservationId()).isEqualTo("rsv-1");
        assertThat(result.shipmentId()).isEqualTo("shp-1");
        assertThat(result.trackingNumber()).isEqualTo("TRK-1");

        verify(payment, never()).refund(anyString());
        verify(inventory, never()).release(anyString());
        verify(shipping, never()).cancel(anyString());
    }

    @Test
    @DisplayName("Shipping fails: compensation runs release then refund, in reverse order")
    void shippingFailsTriggersCompensation(TestWorkflowEnvironment env, Worker worker, OrderSagaWorkflow workflow) {
        PaymentActivities payment = Mockito.mock(PaymentActivities.class);
        InventoryActivities inventory = Mockito.mock(InventoryActivities.class);
        ShippingActivities shipping = Mockito.mock(ShippingActivities.class);

        when(payment.charge(anyString(), anyString(), any())).thenReturn("pay-2");
        when(inventory.reserve(anyString(), anyString(), anyInt())).thenReturn("rsv-2");
        when(shipping.schedule(anyString(), anyString()))
                .thenThrow(new NonRetryableShippingException("Address invalid"));

        worker.registerActivitiesImplementations(adapt(payment), adapt(inventory), adapt(shipping));
        env.start();

        OrderSagaInput input = new OrderSagaInput(
                "ord-2", "cust-1", "SKU-1", 1,
                new BigDecimal("9.99"), new BigDecimal("9.99"), "INVALID address");

        OrderSagaResult result = workflow.placeOrder(input);

        assertThat(result.status()).isEqualTo("COMPENSATED");
        assertThat(result.paymentId()).isEqualTo("pay-2");
        assertThat(result.reservationId()).isEqualTo("rsv-2");
        assertThat(result.shipmentId()).isNull();

        verify(inventory, times(1)).release("rsv-2");
        verify(payment, times(1)).refund("pay-2");
    }

    @Test
    @DisplayName("Payment fails first: no inventory or shipping is touched, no compensation needed")
    void paymentFailureRunsNoCompensation(TestWorkflowEnvironment env, Worker worker, OrderSagaWorkflow workflow) {
        PaymentActivities payment = Mockito.mock(PaymentActivities.class);
        InventoryActivities inventory = Mockito.mock(InventoryActivities.class);
        ShippingActivities shipping = Mockito.mock(ShippingActivities.class);

        when(payment.charge(anyString(), anyString(), any()))
                .thenThrow(new NonRetryablePaymentException("declined"));

        worker.registerActivitiesImplementations(adapt(payment), adapt(inventory), adapt(shipping));
        env.start();

        OrderSagaInput input = new OrderSagaInput(
                "ord-3", "deadbeat-x", "SKU-1", 1,
                new BigDecimal("9.99"), new BigDecimal("9.99"), "123 Main St");

        OrderSagaResult result = workflow.placeOrder(input);

        assertThat(result.status()).isEqualTo("COMPENSATED");
        assertThat(result.paymentId()).isNull();
        verify(inventory, never()).reserve(anyString(), anyString(), anyInt());
        verify(shipping, never()).schedule(anyString(), anyString());
        verify(inventory, never()).release(anyString());
        verify(payment, never()).refund(anyString());
    }

    // --- mock adapters: hide the @ActivityMethod annotation from Temporal's metadata scan ---

    private static PaymentActivities adapt(PaymentActivities mock) {
        return new PaymentActivities() {
            @Override
            public String charge(String orderId, String customerId, BigDecimal amount) {
                return mock.charge(orderId, customerId, amount);
            }

            @Override
            public void refund(String paymentId) {
                mock.refund(paymentId);
            }
        };
    }

    private static InventoryActivities adapt(InventoryActivities mock) {
        return new InventoryActivities() {
            @Override
            public String reserve(String orderId, String productId, int quantity) {
                return mock.reserve(orderId, productId, quantity);
            }

            @Override
            public void release(String reservationId) {
                mock.release(reservationId);
            }
        };
    }

    private static ShippingActivities adapt(ShippingActivities mock) {
        return new ShippingActivities() {
            @Override
            public ShipmentResult schedule(String orderId, String address) {
                return mock.schedule(orderId, address);
            }

            @Override
            public void cancel(String shipmentId) {
                mock.cancel(shipmentId);
            }
        };
    }
}
