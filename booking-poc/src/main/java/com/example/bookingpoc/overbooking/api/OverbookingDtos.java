package com.example.bookingpoc.overbooking.api;

import com.example.bookingpoc.overbooking.domain.FareClass;
import com.example.bookingpoc.overbooking.domain.Flight;
import com.example.bookingpoc.overbooking.domain.FlightBooking;
import com.example.bookingpoc.overbooking.service.BumpingService.BoardingResult;
import com.example.bookingpoc.overbooking.service.OverbookingService.FlightStateView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class OverbookingDtos {

    private OverbookingDtos() {}

    public record FlightView(String code, String origin, String destination, int capacity,
                             BigDecimal noShowRate, BigDecimal overbookFactor, int sellCeiling,
                             Instant departureTime) {
        public static FlightView from(Flight f) {
            return new FlightView(f.getCode(), f.getOrigin(), f.getDestination(),
                    f.getCapacity(), f.getNoShowRate(), f.getOverbookFactor(),
                    f.getSellCeiling(), f.getDepartureTime());
        }
    }

    public record BookRequest(@NotBlank String passengerName,
                              @NotNull FareClass fareClass,
                              @Min(0) @Max(100) int volunteerWillingness) {}

    public record BookingView(Long id, String flightCode, String passengerName,
                              FareClass fareClass, BigDecimal farePaid,
                              int volunteerWillingness, String status,
                              BigDecimal compensationPaid) {
        public static BookingView from(FlightBooking b) {
            return new BookingView(b.getId(), b.getFlightCode(), b.getPassengerName(),
                    b.getFareClass(), b.getFarePaid(), b.getVolunteerWillingness(),
                    b.getStatus().name(), b.getCompensationPaid());
        }
    }

    public record BoardRequest(Set<Long> noShowBookingIds) {}

    public record StateView(String code, int capacity, int sellCeiling,
                            long confirmed, long bumped, long boarded,
                            BigDecimal expectedShowUps, int expectedOverflow) {
        public static StateView from(FlightStateView s) {
            return new StateView(s.code(), s.capacity(), s.sellCeiling(),
                    s.confirmed(), s.bumped(), s.boarded(),
                    s.expectedShowUps(), s.expectedOverflow());
        }
    }

    public record BoardingView(int capacity, int boarded, int voluntaryBumps,
                               int involuntaryBumps, BigDecimal compensationTotal,
                               List<BoardingBumpEntry> bumped) {
        public static BoardingView from(BoardingResult r) {
            return new BoardingView(r.capacity(), r.boarded(),
                    r.voluntaryBumps(), r.involuntaryBumps(),
                    r.compensationTotal(),
                    r.bumped().stream()
                            .map(b -> new BoardingBumpEntry(b.bookingId(), b.passengerName(),
                                    b.fareClass(), b.compensationPaid(), b.kind()))
                            .toList());
        }
    }

    public record BoardingBumpEntry(Long bookingId, String passengerName, String fareClass,
                                    BigDecimal compensationPaid, String kind) {}
}
