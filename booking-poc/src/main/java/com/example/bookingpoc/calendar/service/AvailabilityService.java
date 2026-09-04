package com.example.bookingpoc.calendar.service;

import com.example.bookingpoc.calendar.domain.Booking;
import com.example.bookingpoc.calendar.domain.CalendarOwner;
import com.example.bookingpoc.calendar.domain.RecurrenceException;
import com.example.bookingpoc.calendar.domain.RecurringRule;
import com.example.bookingpoc.calendar.repo.BookingRepository;
import com.example.bookingpoc.calendar.repo.RecurrenceExceptionRepository;
import com.example.bookingpoc.calendar.repo.RecurringRuleRepository;
import com.example.bookingpoc.common.BookingException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Expands the recurring rules + exceptions for a given owner over a date range
 * and subtracts confirmed bookings, returning the still-open slots in UTC.
 *
 * All arithmetic happens at wall-clock (LocalDateTime) granularity in the
 * owner's timezone, then converts to Instant for storage/output. This is the
 * only way DST shifts ("9:00 every Monday" across a spring-forward) come out
 * right.
 */
@Service
public class AvailabilityService {

    private final RecurringRuleRepository ruleRepo;
    private final RecurrenceExceptionRepository exceptionRepo;
    private final BookingRepository bookingRepo;

    public AvailabilityService(RecurringRuleRepository ruleRepo,
                               RecurrenceExceptionRepository exceptionRepo,
                               BookingRepository bookingRepo) {
        this.ruleRepo = ruleRepo;
        this.exceptionRepo = exceptionRepo;
        this.bookingRepo = bookingRepo;
    }

    @Transactional(readOnly = true)
    public List<TimeRange> openSlots(CalendarOwner owner, LocalDate from, LocalDate to,
                                     int slotMinutes) {
        if (from.isAfter(to)) {
            throw BookingException.badRequest("range_invalid", "from must be <= to");
        }
        if (slotMinutes <= 0 || slotMinutes > 240) {
            throw BookingException.badRequest("slot_invalid", "slotMinutes must be in (0, 240]");
        }
        ZoneId zone = ZoneId.of(owner.getTimezone());
        List<RecurringRule> rules = ruleRepo.findByOwnerId(owner.getId());
        if (rules.isEmpty()) return List.of();

        List<Long> ruleIds = rules.stream().map(RecurringRule::getId).toList();
        Set<RuleDateKey> blocked = exceptionRepo
                .findByRuleIdInAndExceptionDateBetween(ruleIds, from, to).stream()
                .map(this::keyOf)
                .collect(Collectors.toSet());

        Instant rangeStartUtc = from.atStartOfDay(zone).toInstant();
        Instant rangeEndUtc = to.plusDays(1).atStartOfDay(zone).toInstant();
        List<Booking> bookings = bookingRepo.findByOwnerIdAndStatusAndStartUtcBetweenOrderByStartUtc(
                owner.getId(), com.example.bookingpoc.calendar.domain.BookingStatus.CONFIRMED,
                rangeStartUtc, rangeEndUtc);

        List<TimeRange> slots = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (RecurringRule rule : rules) {
                if (!appliesOn(rule, date)) continue;
                if (blocked.contains(new RuleDateKey(rule.getId(), date))) continue;
                addSlots(rule, date, zone, slotMinutes, owner.getBufferMinutes(), bookings, slots);
            }
        }
        slots.sort(Comparator.comparing(TimeRange::startUtc));
        return slots;
    }

    private boolean appliesOn(RecurringRule rule, LocalDate date) {
        if (date.isBefore(rule.getValidFrom())) return false;
        if (rule.getValidUntil() != null && date.isAfter(rule.getValidUntil())) return false;
        return date.getDayOfWeek().getValue() == rule.getDayOfWeek();
    }

    private void addSlots(RecurringRule rule, LocalDate date, ZoneId zone, int slotMinutes,
                          int bufferMinutes, List<Booking> bookings, List<TimeRange> out) {
        ZonedDateTime windowStart = date.atTime(rule.getStartLocal()).atZone(zone);
        ZonedDateTime windowEnd = date.atTime(rule.getEndLocal()).atZone(zone);

        ZonedDateTime cursor = windowStart;
        while (!cursor.plusMinutes(slotMinutes).isAfter(windowEnd)) {
            ZonedDateTime slotEnd = cursor.plusMinutes(slotMinutes);
            Instant startUtc = cursor.toInstant();
            Instant endUtc = slotEnd.toInstant();
            if (!collidesWithBookings(startUtc, endUtc, bufferMinutes, bookings)) {
                out.add(new TimeRange(startUtc, endUtc));
            }
            cursor = slotEnd;
        }
    }

    private boolean collidesWithBookings(Instant startUtc, Instant endUtc, int bufferMinutes,
                                         List<Booking> bookings) {
        Instant bufferedStart = startUtc.minus(bufferMinutes, ChronoUnit.MINUTES);
        Instant bufferedEnd = endUtc.plus(bufferMinutes, ChronoUnit.MINUTES);
        for (Booking b : bookings) {
            if (b.getStartUtc().isBefore(bufferedEnd) && b.getEndUtc().isAfter(bufferedStart)) {
                return true;
            }
        }
        return false;
    }

    private RuleDateKey keyOf(RecurrenceException ex) {
        return new RuleDateKey(ex.getRuleId(), ex.getExceptionDate());
    }

    public record TimeRange(Instant startUtc, Instant endUtc) {}

    private record RuleDateKey(Long ruleId, LocalDate date) {}
}
