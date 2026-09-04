package com.example.saga.choreography.inventory.repository;

import com.example.saga.choreography.inventory.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, String> {

    Optional<Reservation> findBySagaId(String sagaId);
}
