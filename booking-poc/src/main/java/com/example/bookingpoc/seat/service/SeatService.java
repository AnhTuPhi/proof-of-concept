package com.example.bookingpoc.seat.service;

import com.example.bookingpoc.common.BookingException;
import com.example.bookingpoc.seat.domain.HardHold;
import com.example.bookingpoc.seat.domain.HardHoldStatus;
import com.example.bookingpoc.seat.domain.Seat;
import com.example.bookingpoc.seat.domain.SeatStatus;
import com.example.bookingpoc.seat.repo.HardHoldRepository;
import com.example.bookingpoc.seat.repo.SeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class SeatService {

    private final SeatRepository seatRepo;
    private final HardHoldRepository holdRepo;
    private final SoftHoldStore softHolds;

    public SeatService(SeatRepository seatRepo, HardHoldRepository holdRepo, SoftHoldStore softHolds) {
        this.seatRepo = seatRepo;
        this.holdRepo = holdRepo;
        this.softHolds = softHolds;
    }

    @Transactional(readOnly = true)
    public List<Seat> list(String resourceId) {
        return seatRepo.findByResourceIdOrderByCode(resourceId);
    }

    /**
     * Stage 1 — soft hold. Cheap in-memory check-and-set; survives mobile drop
     * because TTL releases automatically. Does NOT touch DB. Multiple losers
     * see "already held" instead of fighting a DB row.
     */
    @Transactional(readOnly = true)
    public SoftHoldResult softHold(Long seatId, String customerId) {
        Seat seat = seatRepo.findById(seatId)
                .orElseThrow(() -> BookingException.notFound("seat_missing", "Seat " + seatId + " not found"));
        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw BookingException.conflict("seat_unavailable",
                    "Seat " + seat.getCode() + " is " + seat.getStatus());
        }
        return softHolds.tryAcquire(seatId, customerId)
                .map(h -> new SoftHoldResult(h.token(), h.expiresAt(), seat.getCode()))
                .orElseThrow(() -> BookingException.conflict("seat_held",
                        "Seat " + seat.getCode() + " is currently held by another shopper"));
    }

    /**
     * Stage 2 — promote to hard hold. Caller has the soft-hold token and is
     * initiating payment. We consume the soft hold and write a DB row with
     * the payment deadline. Uses PESSIMISTIC_WRITE on the seat row so two
     * concurrent promotions cannot both flip AVAILABLE → HARD_HELD.
     */
    @Transactional
    public HardHoldResult hardHold(Long seatId, String customerId, String softToken, Duration payWindow) {
        if (!softHolds.consume(seatId, softToken)) {
            throw BookingException.conflict("soft_hold_invalid",
                    "Soft hold expired or token mismatch — restart the checkout");
        }
        Seat seat = seatRepo.findByIdForUpdate(seatId)
                .orElseThrow(() -> BookingException.notFound("seat_missing", "Seat " + seatId + " not found"));
        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw BookingException.conflict("seat_unavailable",
                    "Seat " + seat.getCode() + " is " + seat.getStatus() + " — likely won by another shopper");
        }
        Instant deadline = Instant.now().plus(payWindow);
        HardHold hold = holdRepo.save(new HardHold(seatId, customerId, deadline));
        seat.setStatus(SeatStatus.HARD_HELD);
        return new HardHoldResult(hold.getId(), seat.getCode(), deadline);
    }

    /**
     * Stage 3 — payment confirmed. Marks the seat SOLD and the hold PAID.
     */
    @Transactional
    public void confirmPayment(Long seatId, Long holdId, String customerId) {
        HardHold hold = holdRepo.findById(holdId)
                .orElseThrow(() -> BookingException.notFound("hold_missing", "Hold " + holdId + " not found"));
        if (!hold.getSeatId().equals(seatId) || !hold.getCustomerId().equals(customerId)) {
            throw BookingException.badRequest("hold_mismatch", "Hold does not match the seat+customer");
        }
        if (hold.getStatus() != HardHoldStatus.PENDING) {
            throw BookingException.conflict("hold_not_pending", "Hold is " + hold.getStatus());
        }
        if (hold.getPaymentDeadline().isBefore(Instant.now())) {
            hold.setStatus(HardHoldStatus.EXPIRED);
            throw BookingException.conflict("hold_expired", "Payment window expired before confirmation");
        }
        Seat seat = seatRepo.findByIdForUpdate(seatId)
                .orElseThrow(() -> BookingException.notFound("seat_missing", "Seat " + seatId + " not found"));
        seat.setStatus(SeatStatus.SOLD);
        hold.setStatus(HardHoldStatus.PAID);
    }

    @Transactional
    public void releaseHold(Long seatId, Long holdId) {
        HardHold hold = holdRepo.findById(holdId)
                .orElseThrow(() -> BookingException.notFound("hold_missing", "Hold " + holdId + " not found"));
        if (hold.getStatus() != HardHoldStatus.PENDING) {
            return;
        }
        Seat seat = seatRepo.findByIdForUpdate(seatId)
                .orElseThrow(() -> BookingException.notFound("seat_missing", "Seat " + seatId + " not found"));
        seat.setStatus(SeatStatus.AVAILABLE);
        hold.setStatus(HardHoldStatus.RELEASED);
    }

    /**
     * Sweep PENDING hard holds whose deadline has passed and revert their seats
     * to AVAILABLE. Called by HardHoldSweeper on a fixed schedule.
     */
    @Transactional
    public int sweepExpired() {
        List<HardHold> expired = holdRepo.findAllByStatusAndPaymentDeadlineBefore(
                HardHoldStatus.PENDING, Instant.now());
        int count = 0;
        for (HardHold hold : expired) {
            Seat seat = seatRepo.findByIdForUpdate(hold.getSeatId()).orElse(null);
            if (seat != null && seat.getStatus() == SeatStatus.HARD_HELD) {
                seat.setStatus(SeatStatus.AVAILABLE);
            }
            hold.setStatus(HardHoldStatus.EXPIRED);
            count++;
        }
        return count;
    }

    public record SoftHoldResult(String token, Instant expiresAt, String seatCode) {}
    public record HardHoldResult(Long holdId, String seatCode, Instant paymentDeadline) {}
}
