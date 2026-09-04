package com.example.saga.orchestration.repository;

import com.example.saga.orchestration.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    Optional<Payment> findFirstByOrderIdAndStatus(String orderId, Payment.Status status);
}
