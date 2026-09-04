package com.example.saga.orchestration.repository;

import com.example.saga.orchestration.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, String> {

    Optional<Reservation> findFirstByOrderIdAndStatus(String orderId, Reservation.Status status);
}
