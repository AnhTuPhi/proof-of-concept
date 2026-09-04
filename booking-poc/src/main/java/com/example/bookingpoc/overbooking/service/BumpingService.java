package com.example.bookingpoc.overbooking.service;

import com.example.bookingpoc.common.BookingException;
import com.example.bookingpoc.overbooking.domain.BookingStatus;
import com.example.bookingpoc.overbooking.domain.FareClass;
import com.example.bookingpoc.overbooking.domain.Flight;
import com.example.bookingpoc.overbooking.domain.FlightBooking;
import com.example.bookingpoc.overbooking.repo.FlightBookingRepository;
import com.example.bookingpoc.overbooking.repo.FlightRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Boarding-time bumping engine:
 *
 *   1. If show-ups ≤ capacity → no bumping needed.
 *   2. Otherwise, bump VOLUNTEERS first (sorted by willingness desc, then by
 *      lowest fare class — cheapest seats are also cheapest to comp), paying
 *      the voluntary bonus.
 *   3. If still over, bump INVOLUNTARILY in REVERSE fare-rank order — the
 *      cheapest fare classes go first because that's both legally easier and
 *      financially cheaper. The involuntary compensation is higher.
 *
 * Cost-vs-revenue trade-off: each bump costs `voluntary` or `involuntary`
 * compensation but frees revenue from the overbooked extra ticket. The total
 * compensation paid out is returned so the caller can compare against the
 * revenue gained from selling those extra seats in the first place.
 */
@Service
public class BumpingService {

    private static final Logger log = LoggerFactory.getLogger(BumpingService.class);

    private final FlightRepository flightRepo;
    private final FlightBookingRepository bookingRepo;
    private final BigDecimal voluntaryBonus;
    private final BigDecimal involuntaryCompensation;

    public BumpingService(FlightRepository flightRepo,
                          FlightBookingRepository bookingRepo,
                          @Value("${booking.overbooking.voluntary-bonus:200}") BigDecimal voluntaryBonus,
                          @Value("${booking.overbooking.involuntary-compensation:800}") BigDecimal involuntaryCompensation) {
        this.flightRepo = flightRepo;
        this.bookingRepo = bookingRepo;
        this.voluntaryBonus = voluntaryBonus;
        this.involuntaryCompensation = involuntaryCompensation;
    }

    @Transactional
    public BoardingResult board(String flightCode, Set<Long> noShowIds) {
        Flight flight = flightRepo.findByCodeForUpdate(flightCode)
                .orElseThrow(() -> BookingException.notFound("flight_missing", "Flight " + flightCode + " not found"));
        List<FlightBooking> confirmed = bookingRepo.findByFlightCodeAndStatus(
                flightCode, BookingStatus.CONFIRMED);
        if (confirmed.isEmpty()) {
            return new BoardingResult(flight.getCapacity(), 0, 0, 0, BigDecimal.ZERO, List.of());
        }

        List<FlightBooking> showUps = new ArrayList<>();
        for (FlightBooking b : confirmed) {
            if (noShowIds != null && noShowIds.contains(b.getId())) {
                b.setStatus(BookingStatus.NO_SHOW);
            } else {
                showUps.add(b);
            }
        }

        int capacity = flight.getCapacity();
        int overflow = showUps.size() - capacity;
        List<BumpedView> bumped = new ArrayList<>();
        BigDecimal compTotal = BigDecimal.ZERO;
        int voluntaryCount = 0;
        int involuntaryCount = 0;

        if (overflow > 0) {
            List<FlightBooking> volunteers = new ArrayList<>(showUps);
            volunteers.sort(Comparator
                    .comparingInt(FlightBooking::getVolunteerWillingness).reversed()
                    .thenComparing(b -> rankOf(b.getFareClass()), Comparator.reverseOrder()));

            Iterator<FlightBooking> it = volunteers.iterator();
            while (overflow > 0 && it.hasNext()) {
                FlightBooking v = it.next();
                if (v.getVolunteerWillingness() < 30) break;
                v.setStatus(BookingStatus.BUMPED_VOLUNTARY);
                v.setCompensationPaid(voluntaryBonus);
                compTotal = compTotal.add(voluntaryBonus);
                bumped.add(BumpedView.from(v, "voluntary"));
                showUps.remove(v);
                voluntaryCount++;
                overflow--;
            }
        }

        if (overflow > 0) {
            showUps.sort(Comparator
                    .comparing((FlightBooking b) -> rankOf(b.getFareClass()), Comparator.reverseOrder())
                    .thenComparing(FlightBooking::getCreatedAt, Comparator.reverseOrder()));
            Iterator<FlightBooking> it = showUps.iterator();
            while (overflow > 0 && it.hasNext()) {
                FlightBooking v = it.next();
                v.setStatus(BookingStatus.BUMPED_INVOLUNTARY);
                v.setCompensationPaid(involuntaryCompensation);
                compTotal = compTotal.add(involuntaryCompensation);
                bumped.add(BumpedView.from(v, "involuntary"));
                it.remove();
                involuntaryCount++;
                overflow--;
            }
        }

        for (FlightBooking b : showUps) {
            b.setStatus(BookingStatus.BOARDED);
        }
        log.info("Flight {} boarding: boarded={} voluntary={} involuntary={} comp={}",
                flightCode, showUps.size(), voluntaryCount, involuntaryCount, compTotal);

        return new BoardingResult(capacity, showUps.size(), voluntaryCount, involuntaryCount,
                compTotal, bumped);
    }

    private int rankOf(FareClass fc) {
        return fc.getRank();
    }

    public record BumpedView(Long bookingId, String passengerName, String fareClass,
                             BigDecimal compensationPaid, String kind) {
        public static BumpedView from(FlightBooking b, String kind) {
            return new BumpedView(b.getId(), b.getPassengerName(),
                    b.getFareClass().name(), b.getCompensationPaid(), kind);
        }
    }

    public record BoardingResult(int capacity, int boarded, int voluntaryBumps,
                                 int involuntaryBumps, BigDecimal compensationTotal,
                                 List<BumpedView> bumped) {}
}
