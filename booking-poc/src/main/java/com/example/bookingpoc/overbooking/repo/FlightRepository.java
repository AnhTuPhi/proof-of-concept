package com.example.bookingpoc.overbooking.repo;

import com.example.bookingpoc.overbooking.domain.Flight;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FlightRepository extends JpaRepository<Flight, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Flight f where f.code = :code")
    Optional<Flight> findByCodeForUpdate(@Param("code") String code);
}
