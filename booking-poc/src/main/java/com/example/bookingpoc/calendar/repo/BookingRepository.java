package com.example.bookingpoc.calendar.repo;

import com.example.bookingpoc.calendar.domain.Booking;
import com.example.bookingpoc.calendar.domain.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Overlap test: two intervals overlap iff NOT(newEnd <= existingStart OR newStart >= existingEnd).
     * PESSIMISTIC_WRITE serializes concurrent bookers fighting for the same window.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from Booking b
            where b.ownerId = :ownerId
              and b.status = com.example.bookingpoc.calendar.domain.BookingStatus.CONFIRMED
              and b.startUtc < :endUtc
              and b.endUtc   > :startUtc
            """)
    List<Booking> findOverlappingForUpdate(@Param("ownerId") String ownerId,
                                          @Param("startUtc") Instant startUtc,
                                          @Param("endUtc") Instant endUtc);

    List<Booking> findByOwnerIdAndStatusAndStartUtcBetweenOrderByStartUtc(
            String ownerId, BookingStatus status, Instant from, Instant to);
}
