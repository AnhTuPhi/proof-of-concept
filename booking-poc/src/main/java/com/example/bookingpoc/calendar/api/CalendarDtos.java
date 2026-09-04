package com.example.bookingpoc.calendar.api;

import com.example.bookingpoc.calendar.domain.Booking;
import com.example.bookingpoc.calendar.service.AvailabilityService.TimeRange;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class CalendarDtos {

    private CalendarDtos() {}

    public record SlotView(Instant startUtc, Instant endUtc, String startInOwnerTz) {
        public static SlotView from(TimeRange r, String ownerTz) {
            ZoneId zone = ZoneId.of(ownerTz);
            String startLocal = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(r.startUtc().atZone(zone));
            return new SlotView(r.startUtc(), r.endUtc(), startLocal);
        }
    }

    public record BookRequest(@NotBlank String inviteeName,
                              @NotNull LocalDateTime startInInviteeTz,
                              @Min(5) int durationMinutes,
                              @NotBlank String inviteeTimezone) {}

    public record BookingView(Long id, String ownerId, String inviteeName,
                              Instant startUtc, Instant endUtc, String inviteeTimezone, String status) {
        public static BookingView from(Booking b) {
            return new BookingView(b.getId(), b.getOwnerId(), b.getInviteeName(),
                    b.getStartUtc(), b.getEndUtc(), b.getInviteeTimezone(), b.getStatus().name());
        }
    }
}
