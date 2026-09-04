package com.example.bookingpoc.overbooking.repo;

import com.example.bookingpoc.overbooking.domain.BookingStatus;
import com.example.bookingpoc.overbooking.domain.FlightBooking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FlightBookingRepository extends JpaRepository<FlightBooking, Long> {

    long countByFlightCodeAndStatus(String flightCode, BookingStatus status);

    List<FlightBooking> findByFlightCodeAndStatusOrderByVolunteerWillingnessDesc(
            String flightCode, BookingStatus status);

    List<FlightBooking> findByFlightCodeAndStatus(String flightCode, BookingStatus status);
}
