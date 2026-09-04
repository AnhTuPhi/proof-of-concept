package com.example.bookingpoc.overbooking.service;

import com.example.bookingpoc.common.BookingException;
import com.example.bookingpoc.overbooking.domain.BookingStatus;
import com.example.bookingpoc.overbooking.domain.FareClass;
import com.example.bookingpoc.overbooking.domain.Flight;
import com.example.bookingpoc.overbooking.domain.FlightBooking;
import com.example.bookingpoc.overbooking.repo.FlightBookingRepository;
import com.example.bookingpoc.overbooking.repo.FlightRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Sells tickets up to capacity * (1 + overbookFactor). This is the same
 * probabilistic model the airlines use: P(no-show) * sold ≈ E[empty seats],
 * so as long as overbookFactor stays below the empirical no-show rate the
 * plane usually departs full and the carrier is rarely on the hook for
 * compensation.
 */
@Service
public class OverbookingService {

    private final FlightRepository flightRepo;
    private final FlightBookingRepository bookingRepo;

    public OverbookingService(FlightRepository flightRepo, FlightBookingRepository bookingRepo) {
        this.flightRepo = flightRepo;
        this.bookingRepo = bookingRepo;
    }

    @Transactional
    public FlightBooking book(String flightCode, String passenger, FareClass fareClass,
                              int volunteerWillingness) {
        Flight flight = flightRepo.findByCodeForUpdate(flightCode)
                .orElseThrow(() -> BookingException.notFound("flight_missing", "Flight " + flightCode + " not found"));

        long confirmed = bookingRepo.countByFlightCodeAndStatus(flightCode, BookingStatus.CONFIRMED);
        int ceiling = flight.getSellCeiling();
        if (confirmed >= ceiling) {
            throw BookingException.conflict("sold_out",
                    "Flight " + flightCode + " is at sell ceiling " + ceiling
                            + " (capacity " + flight.getCapacity() + ", overbook factor " + flight.getOverbookFactor() + ")");
        }
        BigDecimal fare = new BigDecimal(fareClass.getBaseFare());
        int willingness = Math.max(0, Math.min(100, volunteerWillingness));
        return bookingRepo.save(new FlightBooking(flightCode, passenger, fareClass, fare, willingness));
    }

    @Transactional(readOnly = true)
    public FlightStateView state(String flightCode) {
        Flight flight = flightRepo.findById(flightCode)
                .orElseThrow(() -> BookingException.notFound("flight_missing", "Flight " + flightCode + " not found"));
        long confirmed = bookingRepo.countByFlightCodeAndStatus(flightCode, BookingStatus.CONFIRMED);
        long bumped = bookingRepo.countByFlightCodeAndStatus(flightCode, BookingStatus.BUMPED_VOLUNTARY)
                + bookingRepo.countByFlightCodeAndStatus(flightCode, BookingStatus.BUMPED_INVOLUNTARY);
        long boarded = bookingRepo.countByFlightCodeAndStatus(flightCode, BookingStatus.BOARDED);

        BigDecimal pNoShow = flight.getNoShowRate();
        BigDecimal expectedShowUps = new BigDecimal(confirmed)
                .multiply(BigDecimal.ONE.subtract(pNoShow))
                .setScale(2, RoundingMode.HALF_UP);
        int overflowExpected = expectedShowUps.subtract(new BigDecimal(flight.getCapacity()))
                .setScale(0, RoundingMode.UP).intValue();

        return new FlightStateView(
                flight.getCode(), flight.getCapacity(), flight.getSellCeiling(),
                confirmed, bumped, boarded,
                expectedShowUps, Math.max(0, overflowExpected));
    }

    public record FlightStateView(String code, int capacity, int sellCeiling,
                                  long confirmed, long bumped, long boarded,
                                  BigDecimal expectedShowUps, int expectedOverflow) {}
}
