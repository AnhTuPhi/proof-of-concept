package com.example.saga.orchestration.repository;

import com.example.saga.orchestration.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, String> {
}
