package com.example.bookingpoc.seat.repo;

import com.example.bookingpoc.seat.domain.HardHold;
import com.example.bookingpoc.seat.domain.HardHoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface HardHoldRepository extends JpaRepository<HardHold, Long> {

    Optional<HardHold> findFirstBySeatIdAndStatus(Long seatId, HardHoldStatus status);

    List<HardHold> findAllByStatusAndPaymentDeadlineBefore(HardHoldStatus status, Instant cutoff);
}
