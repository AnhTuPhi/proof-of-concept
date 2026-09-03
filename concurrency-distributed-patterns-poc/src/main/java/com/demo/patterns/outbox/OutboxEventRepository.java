package com.demo.patterns.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query("select e from OutboxEvent e where e.processedAt is null order by e.id")
    List<OutboxEvent> findUnprocessed(Pageable pageable);

    long countByProcessedAtIsNull();
}
