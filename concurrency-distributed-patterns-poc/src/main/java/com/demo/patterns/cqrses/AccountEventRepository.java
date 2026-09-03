package com.demo.patterns.cqrses;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountEventRepository extends JpaRepository<AccountEvent, Long> {
    List<AccountEvent> findByAggregateIdOrderByVersionAsc(String aggregateId);
}
