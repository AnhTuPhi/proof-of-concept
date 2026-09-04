package com.example.bookingpoc.calendar.repo;

import com.example.bookingpoc.calendar.domain.RecurrenceException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface RecurrenceExceptionRepository extends JpaRepository<RecurrenceException, Long> {
    List<RecurrenceException> findByRuleIdInAndExceptionDateBetween(
            List<Long> ruleIds, LocalDate from, LocalDate to);
}
