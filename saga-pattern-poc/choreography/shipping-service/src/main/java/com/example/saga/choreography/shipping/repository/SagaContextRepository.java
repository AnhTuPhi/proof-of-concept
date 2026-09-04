package com.example.saga.choreography.shipping.repository;

import com.example.saga.choreography.shipping.domain.SagaContext;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaContextRepository extends JpaRepository<SagaContext, String> {
}
