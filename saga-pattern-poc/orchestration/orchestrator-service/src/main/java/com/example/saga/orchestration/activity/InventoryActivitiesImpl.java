package com.example.saga.orchestration.activity;

import com.example.saga.orchestration.domain.Reservation;
import com.example.saga.orchestration.domain.Stock;
import com.example.saga.orchestration.exception.NonRetryableInventoryException;
import com.example.saga.orchestration.repository.ReservationRepository;
import com.example.saga.orchestration.repository.StockRepository;
import io.temporal.spring.boot.ActivityImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Reservation activity. Idempotent on {@code orderId} and {@code Reservation.Status.RESERVED}
 * so retries by Temporal cannot double-decrement stock.
 */
@Component
@ActivityImpl(taskQueues = "ORDER_SAGA_TASK_QUEUE")
@RequiredArgsConstructor
@Slf4j
public class InventoryActivitiesImpl implements InventoryActivities {

    private final ReservationRepository reservationRepository;
    private final StockRepository stockRepository;

    @Value("${saga.inventory.out-of-stock-product-prefix:OUT_OF_STOCK}")
    private String outOfStockPrefix;

    @Value("${saga.inventory.default-on-hand:100}")
    private int defaultOnHand;

    @Override
    @Transactional
    public String reserve(String orderId, String productId, int quantity) {
        var existing = reservationRepository.findFirstByOrderIdAndStatus(orderId, Reservation.Status.RESERVED);
        if (existing.isPresent()) {
            log.info("Reservation for order {} already exists (id {}), idempotent return",
                    orderId, existing.get().getReservationId());
            return existing.get().getReservationId();
        }

        if (productId.startsWith(outOfStockPrefix)) {
            throw new NonRetryableInventoryException("Product " + productId + " is out of stock");
        }

        Stock stock = stockRepository.findForUpdate(productId).orElseGet(() ->
                stockRepository.save(Stock.builder()
                        .productId(productId)
                        .onHand(defaultOnHand)
                        .reserved(0)
                        .build()));

        if (stock.getOnHand() < quantity) {
            throw new NonRetryableInventoryException(
                    "Only " + stock.getOnHand() + " of " + productId + " available, need " + quantity);
        }

        stock.setOnHand(stock.getOnHand() - quantity);
        stock.setReserved(stock.getReserved() + quantity);
        stockRepository.save(stock);

        String reservationId = "rsv-" + UUID.randomUUID();
        Reservation reservation = Reservation.builder()
                .reservationId(reservationId)
                .orderId(orderId)
                .productId(productId)
                .quantity(quantity)
                .status(Reservation.Status.RESERVED)
                .createdAt(Instant.now())
                .build();
        reservationRepository.save(reservation);
        log.info("Reserved {} of {} for order {} (id {})", quantity, productId, orderId, reservationId);
        return reservationId;
    }

    @Override
    @Transactional
    public void release(String reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null) {
            log.warn("Release called for unknown reservationId {} — nothing to do", reservationId);
            return;
        }
        if (reservation.getStatus() == Reservation.Status.RELEASED) {
            log.info("Reservation {} already released — idempotent skip", reservationId);
            return;
        }
        Stock stock = stockRepository.findForUpdate(reservation.getProductId()).orElseThrow();
        stock.setOnHand(stock.getOnHand() + reservation.getQuantity());
        stock.setReserved(stock.getReserved() - reservation.getQuantity());
        stockRepository.save(stock);

        reservation.setStatus(Reservation.Status.RELEASED);
        reservationRepository.save(reservation);
        log.info("Released {} of {} (reservation {})", reservation.getQuantity(),
                reservation.getProductId(), reservationId);
    }
}
