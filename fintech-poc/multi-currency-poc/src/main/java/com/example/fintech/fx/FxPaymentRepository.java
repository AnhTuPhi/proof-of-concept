package com.example.fintech.fx;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FxPaymentRepository extends JpaRepository<FxPayment, String> {
}
