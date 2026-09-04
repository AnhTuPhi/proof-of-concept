package com.example.saga.orchestration.service;

import com.example.saga.common.dto.OrderRequest;
import com.example.saga.common.dto.OrderResponse;
import com.example.saga.common.enums.OrderStatus;
import com.example.saga.common.enums.SagaStatus;
import com.example.saga.orchestration.domain.Order;
import com.example.saga.orchestration.repository.OrderRepository;
import com.example.saga.orchestration.workflow.OrderSagaInput;
import com.example.saga.orchestration.workflow.OrderSagaResult;
import com.example.saga.orchestration.workflow.OrderSagaWorkflow;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * REST-facing service. Persists the local order record, starts the Temporal workflow
 * asynchronously, and returns immediately so callers do not block on the saga.
 *
 * <p>The workflow result is reconciled back into the local order row by
 * {@link #asyncReconcile(WorkflowStub, String)} so REST {@code GET /orders/{id}} stays
 * the source of truth for clients.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderOrchestrationService {

    private final OrderRepository orderRepository;
    private final WorkflowClient workflowClient;

    @Transactional
    public OrderResponse placeOrder(OrderRequest request) {
        String orderId = "ord-" + UUID.randomUUID();
        String workflowId = "order-saga-" + orderId;
        BigDecimal total = request.unitPrice().multiply(BigDecimal.valueOf(request.quantity()));

        Order order = Order.builder()
                .orderId(orderId)
                .workflowId(workflowId)
                .customerId(request.customerId())
                .productId(request.productId())
                .quantity(request.quantity())
                .unitPrice(request.unitPrice())
                .totalAmount(total)
                .shippingAddress(request.shippingAddress())
                .status(Order.Status.PENDING)
                .createdAt(Instant.now())
                .build();
        orderRepository.save(order);

        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(OrderSagaWorkflow.TASK_QUEUE)
                .setWorkflowId(workflowId)
                .setWorkflowExecutionTimeout(Duration.ofMinutes(30))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .build();

        OrderSagaWorkflow workflow = workflowClient.newWorkflowStub(OrderSagaWorkflow.class, options);
        OrderSagaInput input = new OrderSagaInput(
                orderId,
                request.customerId(),
                request.productId(),
                request.quantity(),
                request.unitPrice(),
                total,
                request.shippingAddress());

        WorkflowClient.start(workflow::placeOrder, input);
        order.setStatus(Order.Status.RUNNING);
        orderRepository.save(order);

        WorkflowStub stub = WorkflowStub.fromTyped(workflow);
        asyncReconcile(stub, orderId);

        log.info("Started workflow {} for order {}", workflowId, orderId);
        return toResponse(order);
    }

    private void asyncReconcile(WorkflowStub stub, String orderId) {
        CompletableFuture
                .supplyAsync(() -> stub.getResult(OrderSagaResult.class))
                .whenComplete((result, ex) -> persistResult(orderId, result, ex));
    }

    @Transactional
    public void persistResult(String orderId, OrderSagaResult result, Throwable ex) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) return;
        if (ex != null) {
            order.setStatus(Order.Status.FAILED);
            order.setFailureReason(ex.getMessage());
        } else if ("COMPLETED".equals(result.status())) {
            order.setStatus(Order.Status.COMPLETED);
        } else {
            order.setStatus(Order.Status.COMPENSATED);
            order.setFailureReason(result.failureReason());
        }
        orderRepository.save(order);
        log.info("Reconciled order {} to {}", orderId, order.getStatus());
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + orderId));

        WorkflowExecutionStatus liveStatus = describeWorkflowStatus(order.getWorkflowId());
        if (liveStatus != null && order.getStatus() == Order.Status.RUNNING) {
            switch (liveStatus) {
                case WORKFLOW_EXECUTION_STATUS_COMPLETED -> {
                    OrderSagaResult result = workflowClient
                            .newUntypedWorkflowStub(order.getWorkflowId())
                            .getResult(OrderSagaResult.class);
                    order.setStatus("COMPLETED".equals(result.status())
                            ? Order.Status.COMPLETED : Order.Status.COMPENSATED);
                    if (!"COMPLETED".equals(result.status())) {
                        order.setFailureReason(result.failureReason());
                    }
                    orderRepository.save(order);
                }
                case WORKFLOW_EXECUTION_STATUS_FAILED,
                     WORKFLOW_EXECUTION_STATUS_TERMINATED,
                     WORKFLOW_EXECUTION_STATUS_CANCELED,
                     WORKFLOW_EXECUTION_STATUS_TIMED_OUT -> {
                    order.setStatus(Order.Status.FAILED);
                    order.setFailureReason("Workflow ended in " + liveStatus);
                    orderRepository.save(order);
                }
                default -> { /* still running */ }
            }
        }
        return toResponse(order);
    }

    private WorkflowExecutionStatus describeWorkflowStatus(String workflowId) {
        try {
            return workflowClient.newUntypedWorkflowStub(workflowId)
                    .describe()
                    .getWorkflowExecutionInfo()
                    .getStatus();
        } catch (Exception ex) {
            log.debug("Could not describe workflow {}: {}", workflowId, ex.getMessage());
            return null;
        }
    }

    private OrderResponse toResponse(Order order) {
        OrderStatus orderStatus = switch (order.getStatus()) {
            case PENDING -> OrderStatus.PENDING;
            case RUNNING -> OrderStatus.PENDING;
            case COMPLETED -> OrderStatus.COMPLETED;
            case COMPENSATED -> OrderStatus.CANCELLED;
            case FAILED -> OrderStatus.FAILED;
        };
        SagaStatus sagaStatus = switch (order.getStatus()) {
            case PENDING -> SagaStatus.STARTED;
            case RUNNING -> SagaStatus.PAYMENT_PENDING;
            case COMPLETED -> SagaStatus.COMPLETED;
            case COMPENSATED -> SagaStatus.COMPENSATED;
            case FAILED -> SagaStatus.FAILED;
        };
        return new OrderResponse(
                order.getOrderId(),
                order.getWorkflowId(),
                order.getCustomerId(),
                order.getProductId(),
                order.getQuantity(),
                order.getTotalAmount(),
                orderStatus,
                sagaStatus,
                order.getFailureReason(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
