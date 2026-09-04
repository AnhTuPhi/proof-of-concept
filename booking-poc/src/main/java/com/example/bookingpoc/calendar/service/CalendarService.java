package com.example.bookingpoc.calendar.service;

import com.example.bookingpoc.calendar.domain.Booking;
import com.example.bookingpoc.calendar.domain.BookingStatus;
import com.example.bookingpoc.calendar.domain.CalendarOwner;
import com.example.bookingpoc.calendar.repo.BookingRepository;
import com.example.bookingpoc.calendar.repo.CalendarOwnerRepository;
import com.example.bookingpoc.common.BookingException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class CalendarService {

    private final CalendarOwnerRepository ownerRepo;
    private final BookingRepository bookingRepo;
    private final AvailabilityService availabilityService;

    public CalendarService(CalendarOwnerRepository ownerRepo,
                           BookingRepository bookingRepo,
                           AvailabilityService availabilityService) {
        this.ownerRepo = ownerRepo;
        this.bookingRepo = bookingRepo;
        this.availabilityService = availabilityService;
    }

    @Transactional(readOnly = true)
    public List<AvailabilityService.TimeRange> listOpenSlots(String ownerId, LocalDate from, LocalDate to,
                                                             int slotMinutes) {
        CalendarOwner owner = ownerRepo.findById(ownerId)
                .orElseThrow(() -> BookingException.notFound("owner_missing", "Owner " + ownerId + " not found"));
        return availabilityService.openSlots(owner, from, to, slotMinutes);
    }

    /**
     * Two bookers picking the same 30-min slot at the exact same millisecond:
     * PESSIMISTIC_WRITE on the overlap query serializes them. Loser sees
     * "slot_taken" and is told to re-fetch availability.
     */
    @Transactional
    public Booking book(String ownerId, String inviteeName, LocalDateTime localStart,
                        int durationMinutes, String inviteeTimezone) {
        CalendarOwner owner = ownerRepo.findById(ownerId)
                .orElseThrow(() -> BookingException.notFound("owner_missing", "Owner " + ownerId + " not found"));
        if (durationMinutes <= 0 || durationMinutes > 240) {
            throw BookingException.badRequest("duration_invalid", "duration must be in (0, 240] minutes");
        }
        ZoneId inviteeZone = safeZone(inviteeTimezone);

        Instant startUtc = localStart.atZone(inviteeZone).toInstant();
        Instant endUtc = startUtc.plus(durationMinutes, ChronoUnit.MINUTES);

        Instant bufferedStart = startUtc.minus(owner.getBufferMinutes(), ChronoUnit.MINUTES);
        Instant bufferedEnd = endUtc.plus(owner.getBufferMinutes(), ChronoUnit.MINUTES);

        List<Booking> overlap = bookingRepo.findOverlappingForUpdate(ownerId, bufferedStart, bufferedEnd);
        if (!overlap.isEmpty()) {
            Booking collide = overlap.get(0);
            throw BookingException.conflict("slot_taken",
                    "Owner already has " + collide.getInviteeName() + " booked from "
                            + collide.getStartUtc() + " to " + collide.getEndUtc());
        }
        return bookingRepo.save(new Booking(ownerId, inviteeName, startUtc, endUtc, inviteeZone.getId()));
    }

    @Transactional
    public void cancel(Long bookingId) {
        Booking b = bookingRepo.findById(bookingId)
                .orElseThrow(() -> BookingException.notFound("booking_missing", "Booking " + bookingId + " not found"));
        b.setStatus(BookingStatus.CANCELLED);
    }

    @Transactional(readOnly = true)
    public List<Booking> listBookings(String ownerId, LocalDate from, LocalDate to) {
        CalendarOwner owner = ownerRepo.findById(ownerId)
                .orElseThrow(() -> BookingException.notFound("owner_missing", "Owner " + ownerId + " not found"));
        ZoneId zone = ZoneId.of(owner.getTimezone());
        Instant rangeStartUtc = from.atStartOfDay(zone).toInstant();
        Instant rangeEndUtc = to.plusDays(1).atStartOfDay(zone).toInstant();
        return bookingRepo.findByOwnerIdAndStatusAndStartUtcBetweenOrderByStartUtc(
                ownerId, BookingStatus.CONFIRMED, rangeStartUtc, rangeEndUtc);
    }

    private ZoneId safeZone(String tz) {
        if (tz == null || tz.isBlank()) {
            throw BookingException.badRequest("tz_missing", "inviteeTimezone is required (IANA id)");
        }
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException ex) {
            throw BookingException.badRequest("tz_invalid", "Invalid IANA timezone: " + tz);
        }
    }
}
