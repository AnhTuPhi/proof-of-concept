package com.example.saga.choreography.payment.repository;

import com.example.saga.choreography.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    Optional<Payment> findBySagaId(String sagaId);
}
