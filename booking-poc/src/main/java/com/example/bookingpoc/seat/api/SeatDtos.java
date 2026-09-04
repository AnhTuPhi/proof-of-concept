package com.example.bookingpoc.seat.api;

import com.example.bookingpoc.seat.domain.Seat;
import com.example.bookingpoc.seat.service.SeatService.HardHoldResult;
import com.example.bookingpoc.seat.service.SeatService.SoftHoldResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public final class SeatDtos {

    private SeatDtos() {}

    public record SeatView(Long id, String code, String type, String resourceId, String status) {
        public static SeatView from(Seat s) {
            return new SeatView(s.getId(), s.getCode(), s.getType().name(),
                    s.getResourceId(), s.getStatus().name());
        }
    }

    public record SoftHoldRequest(@NotBlank String customerId) {}
    public record SoftHoldResponse(String seatCode, String token, Instant expiresAt) {
        public static SoftHoldResponse from(SoftHoldResult r) {
            return new SoftHoldResponse(r.seatCode(), r.token(), r.expiresAt());
        }
    }

    public record HardHoldRequest(@NotBlank String customerId,
                                  @NotBlank String softToken,
                                  Integer paymentWindowSeconds) {}
    public record HardHoldResponse(Long holdId, String seatCode, Instant paymentDeadline) {
        public static HardHoldResponse from(HardHoldResult r) {
            return new HardHoldResponse(r.holdId(), r.seatCode(), r.paymentDeadline());
        }
    }

    public record ConfirmPaymentRequest(@NotBlank String customerId, @NotNull Long holdId) {}
}
