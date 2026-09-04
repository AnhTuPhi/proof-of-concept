package com.example.bookingpoc.calendar.api;

import com.example.bookingpoc.calendar.api.CalendarDtos.BookRequest;
import com.example.bookingpoc.calendar.api.CalendarDtos.BookingView;
import com.example.bookingpoc.calendar.api.CalendarDtos.SlotView;
import com.example.bookingpoc.calendar.domain.CalendarOwner;
import com.example.bookingpoc.calendar.repo.CalendarOwnerRepository;
import com.example.bookingpoc.calendar.service.CalendarService;
import com.example.bookingpoc.common.BookingException;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

    private final CalendarService calendarService;
    private final CalendarOwnerRepository ownerRepo;

    public CalendarController(CalendarService calendarService, CalendarOwnerRepository ownerRepo) {
        this.calendarService = calendarService;
        this.ownerRepo = ownerRepo;
    }

    @GetMapping("/owners")
    public List<CalendarOwnerView> owners() {
        return ownerRepo.findAll().stream()
                .map(o -> new CalendarOwnerView(o.getId(), o.getDisplayName(), o.getTimezone(), o.getBufferMinutes()))
                .toList();
    }

    @GetMapping("/owners/{ownerId}/slots")
    public List<SlotView> slots(@PathVariable String ownerId,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @RequestParam(defaultValue = "30") int slotMinutes) {
        CalendarOwner owner = ownerRepo.findById(ownerId)
                .orElseThrow(() -> BookingException.notFound("owner_missing", "Owner " + ownerId + " not found"));
        return calendarService.listOpenSlots(ownerId, from, to, slotMinutes).stream()
                .map(r -> SlotView.from(r, owner.getTimezone()))
                .toList();
    }

    @PostMapping("/owners/{ownerId}/bookings")
    public BookingView book(@PathVariable String ownerId, @Valid @RequestBody BookRequest req) {
        return BookingView.from(calendarService.book(
                ownerId, req.inviteeName(), req.startInInviteeTz(),
                req.durationMinutes(), req.inviteeTimezone()));
    }

    @GetMapping("/owners/{ownerId}/bookings")
    public List<BookingView> listBookings(@PathVariable String ownerId,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return calendarService.listBookings(ownerId, from, to).stream().map(BookingView::from).toList();
    }

    @DeleteMapping("/bookings/{bookingId}")
    public void cancel(@PathVariable Long bookingId) {
        calendarService.cancel(bookingId);
    }

    public record CalendarOwnerView(String id, String displayName, String timezone, int bufferMinutes) {}
}
