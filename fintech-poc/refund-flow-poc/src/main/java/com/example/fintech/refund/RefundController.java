package com.example.fintech.refund;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/refunds")
public class RefundController {

    private final RefundService service;

    public RefundController(RefundService service) {
        this.service = service;
    }

    @PostMapping
    public Refund create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody RefundRequest request) {
        return service.refund(idempotencyKey, request);
    }
}
