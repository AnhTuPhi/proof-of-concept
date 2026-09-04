package com.example.saga.choreography.inventory.service;

import com.example.saga.choreography.inventory.domain.ProcessedEvent;
import com.example.saga.choreography.inventory.domain.Reservation;
import com.example.saga.choreography.inventory.domain.SagaContext;
import com.example.saga.choreography.inventory.domain.Stock;
import com.example.saga.choreography.inventory.messaging.SagaEventPublisher;
import com.example.saga.choreography.inventory.repository.ProcessedEventRepository;
import com.example.saga.choreography.inventory.repository.ReservationRepository;
import com.example.saga.choreography.inventory.repository.SagaContextRepository;
import com.example.saga.choreography.inventory.repository.StockRepository;
import com.example.saga.common.enums.FailureReason;
import com.example.saga.common.events.InventoryFailed;
import com.example.saga.common.events.InventoryReleased;
import com.example.saga.common.events.InventoryReserved;
import com.example.saga.common.events.OrderCreated;
import com.example.saga.common.events.PaymentCompleted;
import com.example.saga.common.events.SagaEvent;
import com.example.saga.common.events.ShippingFailed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Reserves and releases stock for the choreography saga.
 *
 * <p>{@link OrderCreated} populates a local {@link SagaContext} row so the service has
 * the product/quantity needed when {@link PaymentCompleted} arrives. Forward path emits
 * {@link InventoryReserved} or {@link InventoryFailed}; compensation on
 * {@link ShippingFailed} reverses the booking and emits {@link InventoryReleased}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final StockRepository stockRepository;
    private final ReservationRepository reservationRepository;
    private final SagaContextRepository sagaContextRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final SagaEventPublisher publisher;

    @Value("${inventory.out-of-stock-product-prefix:OUT_OF_STOCK}")
    private String outOfStockPrefix;

    @Value("${inventory.default-on-hand:100}")
    private int defaultOnHand;

    @Transactional
    public void handle(SagaEvent event) {
        MDC.put("sagaId", event.sagaId());
        try {
            if (processedEventRepository.existsById(event.eventId())) {
                log.debug("Skipping duplicate event {}", event.eventId());
                return;
            }

            switch (event) {
                case OrderCreated e     -> rememberContext(e);
                case PaymentCompleted e -> reserve(e);
                case ShippingFailed e   -> release(e);
                default -> { /* not interested */ }
            }

            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(event.eventId())
                    .sagaId(event.sagaId())
                    .eventType(event.getClass().getSimpleName())
                    .processedAt(Instant.now())
                    .build());
        } finally {
            MDC.remove("sagaId");
        }
    }

    private void rememberContext(OrderCreated event) {
        sagaContextRepository.save(SagaContext.builder()
                .sagaId(event.sagaId())
                .orderId(event.orderId())
                .productId(event.productId())
                .quantity(event.quantity())
                .createdAt(Instant.now())
                .build());
    }

    private void reserve(PaymentCompleted event) {
        SagaContext ctx = sagaContextRepository.findById(event.sagaId()).orElse(null);
        if (ctx == null) {
            log.warn("No saga context for saga {} — OrderCreated may not have arrived yet. Retry will pick this up.",
                    event.sagaId());
            throw new IllegalStateException("Missing saga context for " + event.sagaId());
        }
        if (reservationRepository.findBySagaId(event.sagaId()).isPresent()) {
            log.info("Reservation for saga {} already exists — skipping", event.sagaId());
            return;
        }

        String productId = ctx.getProductId();
        int quantity = ctx.getQuantity();

        if (productId.startsWith(outOfStockPrefix)) {
            publisher.publish(new InventoryFailed(
                    UUID.randomUUID().toString(),
                    event.sagaId(),
                    event.orderId(),
                    productId,
                    quantity,
                    FailureReason.OUT_OF_STOCK,
                    "Product " + productId + " is out of stock (simulated)",
                    Instant.now()));
            return;
        }

        Stock stock = stockRepository.findForUpdate(productId).orElseGet(() ->
                stockRepository.save(Stock.builder()
                        .productId(productId)
                        .onHand(defaultOnHand)
                        .reserved(0)
                        .build()));

        if (stock.getOnHand() < quantity) {
            publisher.publish(new InventoryFailed(
                    UUID.randomUUID().toString(),
                    event.sagaId(),
                    event.orderId(),
                    productId,
                    quantity,
                    FailureReason.OUT_OF_STOCK,
                    "Only " + stock.getOnHand() + " of " + productId + " available",
                    Instant.now()));
            return;
        }

        stock.setOnHand(stock.getOnHand() - quantity);
        stock.setReserved(stock.getReserved() + quantity);
        stockRepository.save(stock);

        Reservation reservation = Reservation.builder()
                .reservationId("rsv-" + UUID.randomUUID())
                .sagaId(event.sagaId())
                .orderId(event.orderId())
                .productId(productId)
                .quantity(quantity)
                .status(Reservation.Status.RESERVED)
                .createdAt(Instant.now())
                .build();
        reservationRepository.save(reservation);

        publisher.publish(new InventoryReserved(
                UUID.randomUUID().toString(),
                event.sagaId(),
                event.orderId(),
                productId,
                quantity,
                reservation.getReservationId(),
                Instant.now()));
    }

    private void release(ShippingFailed event) {
        Reservation reservation = reservationRepository.findBySagaId(event.sagaId()).orElse(null);
        if (reservation == null || reservation.getStatus() != Reservation.Status.RESERVED) {
            log.info("No active reservation to release for saga {}", event.sagaId());
            return;
        }

        Stock stock = stockRepository.findForUpdate(reservation.getProductId()).orElseThrow();
        stock.setOnHand(stock.getOnHand() + reservation.getQuantity());
        stock.setReserved(stock.getReserved() - reservation.getQuantity());
        stockRepository.save(stock);

        reservation.setStatus(Reservation.Status.RELEASED);
        reservationRepository.save(reservation);

        publisher.publish(new InventoryReleased(
                UUID.randomUUID().toString(),
                event.sagaId(),
                event.orderId(),
                reservation.getProductId(),
                reservation.getQuantity(),
                reservation.getReservationId(),
                Instant.now()));
        log.info("Released {} units of {} for saga {}",
                reservation.getQuantity(), reservation.getProductId(), event.sagaId());
    }
}
