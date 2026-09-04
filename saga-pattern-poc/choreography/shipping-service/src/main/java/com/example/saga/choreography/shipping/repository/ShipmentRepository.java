package com.example.saga.choreography.shipping.repository;

import com.example.saga.choreography.shipping.domain.Shipment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, String> {

    Optional<Shipment> findBySagaId(String sagaId);
}
