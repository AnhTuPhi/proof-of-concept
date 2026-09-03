package com.example.fintech.payment;

import com.example.fintech.common.IdempotencyHasher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class PaymentService {

    private final IdempotencyRecordRepository idempotencyRepo;
    private final PaymentRepository paymentRepo;
    private final ObjectMapper mapper;

    public PaymentService(IdempotencyRecordRepository idempotencyRepo,
                          PaymentRepository paymentRepo,
                          ObjectMapper mapper) {
        this.idempotencyRepo = idempotencyRepo;
        this.paymentRepo = paymentRepo;
        this.mapper = mapper;
    }

    public PaymentResponse charge(String idempotencyKey, PaymentRequest request) {
        String fingerprint = IdempotencyHasher.fingerprint(request);

        Optional<IdempotencyRecord> existing = idempotencyRepo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return handleExisting(existing.get(), fingerprint);
        }

        IdempotencyRecord claim;
        try {
            claim = claimKey(idempotencyKey, fingerprint);
        } catch (DataIntegrityViolationException race) {
            IdempotencyRecord winner = idempotencyRepo.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Lost the race but record vanished"));
            return handleExisting(winner, fingerprint);
        }

        try {
            Payment payment = executeCharge(request);
            PaymentResponse response = PaymentResponse.from(payment, false);
            completeClaim(claim.getId(), response);
            return response;
        } catch (RuntimeException e) {
            failClaim(claim.getId());
            throw e;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecord claimKey(String key, String fingerprint) {
        IdempotencyRecord record = new IdempotencyRecord(key, fingerprint);
        return idempotencyRepo.saveAndFlush(record);
    }

    @Transactional
    public Payment executeCharge(PaymentRequest request) {
        Payment payment = Payment.create(request.amount(), request.currency(), request.customerId());
        payment.transitionTo(PaymentStatus.AUTHORIZED);
        payment.transitionTo(PaymentStatus.CAPTURED);
        return paymentRepo.save(payment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeClaim(Long recordId, PaymentResponse response) {
        IdempotencyRecord record = idempotencyRepo.findById(recordId).orElseThrow();
        try {
            record.complete(mapper.writeValueAsString(response));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        idempotencyRepo.save(record);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failClaim(Long recordId) {
        idempotencyRepo.findById(recordId).ifPresent(r -> {
            r.fail();
            idempotencyRepo.save(r);
        });
    }

    private PaymentResponse handleExisting(IdempotencyRecord record, String fingerprint) {
        if (!record.getRequestFingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(
                    "Idempotency key " + record.getIdempotencyKey()
                            + " was previously used with a different request body");
        }
        return switch (record.getStatus()) {
            case COMPLETED -> deserialize(record.getResponseJson());
            case IN_FLIGHT -> throw new IdempotencyConflictException(
                    "Request with key " + record.getIdempotencyKey() + " is still in flight; retry shortly");
            case FAILED -> throw new IdempotencyConflictException(
                    "Previous attempt for key " + record.getIdempotencyKey() + " failed; use a new key");
        };
    }

    private PaymentResponse deserialize(String json) {
        try {
            PaymentResponse original = mapper.readValue(json, PaymentResponse.class);
            return new PaymentResponse(original.id(), original.amount(), original.currency(),
                    original.status(), original.customerId(), true);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored response is unreadable", e);
        }
    }
}
