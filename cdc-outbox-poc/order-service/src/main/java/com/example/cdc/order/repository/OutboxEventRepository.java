package com.example.cdc.order.repository;

import com.example.cdc.order.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.createdAt < :threshold")
    int deleteOlderThan(@Param("threshold") Instant threshold);
}
