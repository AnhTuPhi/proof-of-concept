package com.example.saga.orchestration.repository;

import com.example.saga.orchestration.domain.Shipment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, String> {

    Optional<Shipment> findFirstByOrderIdAndStatus(String orderId, Shipment.Status status);
}
