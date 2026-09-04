package com.example.bookingpoc.seat.api;

import com.example.bookingpoc.seat.api.SeatDtos.ConfirmPaymentRequest;
import com.example.bookingpoc.seat.api.SeatDtos.HardHoldRequest;
import com.example.bookingpoc.seat.api.SeatDtos.HardHoldResponse;
import com.example.bookingpoc.seat.api.SeatDtos.SeatView;
import com.example.bookingpoc.seat.api.SeatDtos.SoftHoldRequest;
import com.example.bookingpoc.seat.api.SeatDtos.SoftHoldResponse;
import com.example.bookingpoc.seat.service.SeatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/seats")
public class SeatController {

    private final SeatService seatService;

    public SeatController(SeatService seatService) {
        this.seatService = seatService;
    }

    @GetMapping
    public List<SeatView> list(@RequestParam String resourceId) {
        return seatService.list(resourceId).stream().map(SeatView::from).toList();
    }

    @PostMapping("/{id}/soft-hold")
    public SoftHoldResponse softHold(@PathVariable Long id, @Valid @RequestBody SoftHoldRequest req) {
        return SoftHoldResponse.from(seatService.softHold(id, req.customerId()));
    }

    @PostMapping("/{id}/hard-hold")
    public HardHoldResponse hardHold(@PathVariable Long id, @Valid @RequestBody HardHoldRequest req) {
        Duration window = Duration.ofSeconds(req.paymentWindowSeconds() == null ? 120 : req.paymentWindowSeconds());
        return HardHoldResponse.from(seatService.hardHold(id, req.customerId(), req.softToken(), window));
    }

    @PostMapping("/{id}/pay")
    public void pay(@PathVariable Long id, @Valid @RequestBody ConfirmPaymentRequest req) {
        seatService.confirmPayment(id, req.holdId(), req.customerId());
    }

    @PostMapping("/{id}/release/{holdId}")
    public void release(@PathVariable Long id, @PathVariable Long holdId) {
        seatService.releaseHold(id, holdId);
    }
}
