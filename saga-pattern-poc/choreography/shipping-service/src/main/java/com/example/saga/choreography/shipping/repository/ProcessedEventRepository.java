package com.example.saga.choreography.shipping.repository;

import com.example.saga.choreography.shipping.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
