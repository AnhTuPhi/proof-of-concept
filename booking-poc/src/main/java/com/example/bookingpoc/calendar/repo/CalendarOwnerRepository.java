package com.example.bookingpoc.calendar.repo;

import com.example.bookingpoc.calendar.domain.CalendarOwner;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarOwnerRepository extends JpaRepository<CalendarOwner, String> {
}
