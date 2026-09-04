package com.example.bookingpoc.overbooking.api;

import com.example.bookingpoc.overbooking.api.OverbookingDtos.BoardRequest;
import com.example.bookingpoc.overbooking.api.OverbookingDtos.BoardingView;
import com.example.bookingpoc.overbooking.api.OverbookingDtos.BookRequest;
import com.example.bookingpoc.overbooking.api.OverbookingDtos.BookingView;
import com.example.bookingpoc.overbooking.api.OverbookingDtos.FlightView;
import com.example.bookingpoc.overbooking.api.OverbookingDtos.StateView;
import com.example.bookingpoc.overbooking.repo.FlightBookingRepository;
import com.example.bookingpoc.overbooking.repo.FlightRepository;
import com.example.bookingpoc.overbooking.service.BumpingService;
import com.example.bookingpoc.overbooking.service.OverbookingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/overbooking")
public class OverbookingController {

    private final OverbookingService overbookingService;
    private final BumpingService bumpingService;
    private final FlightRepository flightRepo;
    private final FlightBookingRepository bookingRepo;

    public OverbookingController(OverbookingService overbookingService,
                                 BumpingService bumpingService,
                                 FlightRepository flightRepo,
                                 FlightBookingRepository bookingRepo) {
        this.overbookingService = overbookingService;
        this.bumpingService = bumpingService;
        this.flightRepo = flightRepo;
        this.bookingRepo = bookingRepo;
    }

    @GetMapping("/flights")
    public List<FlightView> flights() {
        return flightRepo.findAll().stream().map(FlightView::from).toList();
    }

    @GetMapping("/flights/{code}")
    public StateView state(@PathVariable String code) {
        return StateView.from(overbookingService.state(code));
    }

    @GetMapping("/flights/{code}/bookings")
    public List<BookingView> bookings(@PathVariable String code) {
        return bookingRepo.findByFlightCodeAndStatusOrderByVolunteerWillingnessDesc(
                code, com.example.bookingpoc.overbooking.domain.BookingStatus.CONFIRMED).stream()
                .map(BookingView::from).toList();
    }

    @PostMapping("/flights/{code}/book")
    public BookingView book(@PathVariable String code, @Valid @RequestBody BookRequest req) {
        return BookingView.from(overbookingService.book(
                code, req.passengerName(), req.fareClass(), req.volunteerWillingness()));
    }

    @PostMapping("/flights/{code}/board")
    public BoardingView board(@PathVariable String code, @RequestBody(required = false) BoardRequest req) {
        Set<Long> noShows = req == null || req.noShowBookingIds() == null ? Set.of() : req.noShowBookingIds();
        return BoardingView.from(bumpingService.board(code, noShows));
    }
}
