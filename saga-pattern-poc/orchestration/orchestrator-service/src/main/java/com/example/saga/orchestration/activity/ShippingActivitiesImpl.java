package com.example.saga.orchestration.activity;

import com.example.saga.orchestration.domain.Shipment;
import com.example.saga.orchestration.exception.NonRetryableShippingException;
import com.example.saga.orchestration.repository.ShipmentRepository;
import io.temporal.spring.boot.ActivityImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Component
@ActivityImpl(taskQueues = "ORDER_SAGA_TASK_QUEUE")
@RequiredArgsConstructor
@Slf4j
public class ShippingActivitiesImpl implements ShippingActivities {

    private final ShipmentRepository shipmentRepository;

    @Value("${saga.shipping.invalid-address-keyword:INVALID}")
    private String invalidAddressKeyword;

    @Override
    @Transactional
    public ShipmentResult schedule(String orderId, String address) {
        var existing = shipmentRepository.findFirstByOrderIdAndStatus(orderId, Shipment.Status.SCHEDULED);
        if (existing.isPresent()) {
            log.info("Shipment for order {} already scheduled (id {}), idempotent return",
                    orderId, existing.get().getShipmentId());
            return new ShipmentResult(existing.get().getShipmentId(), existing.get().getTrackingNumber());
        }

        if (address != null && address.toUpperCase().contains(invalidAddressKeyword)) {
            throw new NonRetryableShippingException("Address could not be validated: " + address);
        }

        String shipmentId = "shp-" + UUID.randomUUID();
        String tracking = "TRK-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        Instant eta = Instant.now().plus(3, ChronoUnit.DAYS);

        Shipment shipment = Shipment.builder()
                .shipmentId(shipmentId)
                .orderId(orderId)
                .address(address)
                .trackingNumber(tracking)
                .estimatedDelivery(eta)
                .status(Shipment.Status.SCHEDULED)
                .createdAt(Instant.now())
                .build();
        shipmentRepository.save(shipment);
        log.info("Scheduled shipment {} for order {} tracking {}", shipmentId, orderId, tracking);
        return new ShipmentResult(shipmentId, tracking);
    }

    @Override
    @Transactional
    public void cancel(String shipmentId) {
        Shipment shipment = shipmentRepository.findById(shipmentId).orElse(null);
        if (shipment == null) {
            log.warn("Cancel called for unknown shipmentId {} — nothing to do", shipmentId);
            return;
        }
        if (shipment.getStatus() == Shipment.Status.CANCELLED) {
            log.info("Shipment {} already cancelled — idempotent skip", shipmentId);
            return;
        }
        shipment.setStatus(Shipment.Status.CANCELLED);
        shipmentRepository.save(shipment);
        log.info("Cancelled shipment {}", shipmentId);
    }
}
