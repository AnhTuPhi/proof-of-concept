package com.example.fintech.refund;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OriginalPaymentRepository extends JpaRepository<OriginalPayment, String> {
}
