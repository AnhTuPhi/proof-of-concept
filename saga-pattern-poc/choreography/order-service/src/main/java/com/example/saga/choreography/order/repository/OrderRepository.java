package com.example.saga.choreography.order.repository;

import com.example.saga.choreography.order.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, String> {

    Optional<Order> findBySagaId(String sagaId);
}
