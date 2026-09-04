package com.example.saga.choreography.inventory.repository;

import com.example.saga.choreography.inventory.domain.SagaContext;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaContextRepository extends JpaRepository<SagaContext, String> {
}
