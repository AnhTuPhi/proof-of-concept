package com.example.saga.choreography.shipping.service;

import com.example.saga.choreography.shipping.domain.ProcessedEvent;
import com.example.saga.choreography.shipping.domain.SagaContext;
import com.example.saga.choreography.shipping.domain.Shipment;
import com.example.saga.choreography.shipping.messaging.SagaEventPublisher;
import com.example.saga.choreography.shipping.repository.ProcessedEventRepository;
import com.example.saga.choreography.shipping.repository.SagaContextRepository;
import com.example.saga.choreography.shipping.repository.ShipmentRepository;
import com.example.saga.common.enums.FailureReason;
import com.example.saga.common.events.InventoryReserved;
import com.example.saga.common.events.OrderCreated;
import com.example.saga.common.events.SagaEvent;
import com.example.saga.common.events.ShippingFailed;
import com.example.saga.common.events.ShippingScheduled;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShippingService {

    private final ShipmentRepository shipmentRepository;
    private final SagaContextRepository sagaContextRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final SagaEventPublisher publisher;

    @Value("${shipping.invalid-address-keyword:INVALID}")
    private String invalidAddressKeyword;

    @Transactional
    public void handle(SagaEvent event) {
        MDC.put("sagaId", event.sagaId());
        try {
            if (processedEventRepository.existsById(event.eventId())) {
                log.debug("Skipping duplicate event {}", event.eventId());
                return;
            }

            switch (event) {
                case OrderCreated e      -> rememberContext(e);
                case InventoryReserved e -> schedule(e);
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
                .address(event.shippingAddress())
                .createdAt(Instant.now())
                .build());
    }

    private void schedule(InventoryReserved event) {
        SagaContext ctx = sagaContextRepository.findById(event.sagaId()).orElse(null);
        if (ctx == null) {
            throw new IllegalStateException("Missing saga context for " + event.sagaId());
        }
        if (shipmentRepository.findBySagaId(event.sagaId()).isPresent()) {
            log.info("Shipment for saga {} already exists — skipping", event.sagaId());
            return;
        }

        if (ctx.getAddress() != null && ctx.getAddress().toUpperCase().contains(invalidAddressKeyword)) {
            Shipment failed = Shipment.builder()
                    .shipmentId("shp-" + UUID.randomUUID())
                    .sagaId(event.sagaId())
                    .orderId(event.orderId())
                    .address(ctx.getAddress())
                    .status(Shipment.Status.FAILED)
                    .failureReason("Invalid address (simulated)")
                    .createdAt(Instant.now())
                    .build();
            shipmentRepository.save(failed);

            publisher.publish(new ShippingFailed(
                    UUID.randomUUID().toString(),
                    event.sagaId(),
                    event.orderId(),
                    FailureReason.INVALID_ADDRESS,
                    "Address " + ctx.getAddress() + " could not be validated",
                    Instant.now()));
            return;
        }

        String tracking = "TRK-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        Instant eta = Instant.now().plus(3, ChronoUnit.DAYS);

        Shipment shipment = Shipment.builder()
                .shipmentId("shp-" + UUID.randomUUID())
                .sagaId(event.sagaId())
                .orderId(event.orderId())
                .address(ctx.getAddress())
                .trackingNumber(tracking)
                .estimatedDelivery(eta)
                .status(Shipment.Status.SCHEDULED)
                .createdAt(Instant.now())
                .build();
        shipmentRepository.save(shipment);

        publisher.publish(new ShippingScheduled(
                UUID.randomUUID().toString(),
                event.sagaId(),
                event.orderId(),
                shipment.getShipmentId(),
                tracking,
                eta,
                Instant.now()));
    }
}
