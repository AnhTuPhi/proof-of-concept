package com.example.bookingpoc.calendar.repo;

import com.example.bookingpoc.calendar.domain.RecurringRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecurringRuleRepository extends JpaRepository<RecurringRule, Long> {
    List<RecurringRule> findByOwnerId(String ownerId);
}
