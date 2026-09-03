package com.example.fintech.refund;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, String> {
    Optional<Refund> findByIdempotencyKey(String key);
    List<Refund> findByPaymentId(String paymentId);
}
