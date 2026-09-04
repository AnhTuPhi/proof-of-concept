package com.example.espoc.sync.controller;

import com.example.espoc.sync.model.dto.ProductDto;
import com.example.espoc.sync.strategy.cdc.CdcSyncService;
import com.example.espoc.sync.strategy.naive.DualWriteSyncService;
import com.example.espoc.sync.strategy.outbox.OutboxSyncService;
import com.example.espoc.sync.support.FailureInjector;
import com.example.espoc.sync.support.FailureInjector.Target;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Single entry point to drive all three strategies. The endpoint path picks the strategy.
 *
 * <p>HTTP header {@code X-Inject-Failure: es-fail|kafka-fail|db-rollback} simulates a downstream
 * outage for the *next request only*.
 */
@RestController
@RequestMapping("/api/v1/sync")
public class SyncDemoController {

    private final DualWriteSyncService naiveSvc;
    private final OutboxSyncService outboxSvc;
    private final CdcSyncService cdcSvc;
    private final FailureInjector failures;

    public SyncDemoController(DualWriteSyncService naiveSvc,
                              OutboxSyncService outboxSvc,
                              CdcSyncService cdcSvc,
                              FailureInjector failures) {
        this.naiveSvc = naiveSvc;
        this.outboxSvc = outboxSvc;
        this.cdcSvc = cdcSvc;
        this.failures = failures;
    }

    @PostMapping("/naive/products")
    public ResponseEntity<ProductDto> postNaive(@RequestHeader(value = "X-Inject-Failure", required = false) String inject,
                                                @Valid @RequestBody ProductDto in) {
        injectFailures(inject);
        return ResponseEntity.ok(naiveSvc.save(in));
    }

    @PostMapping("/outbox/products")
    public ResponseEntity<ProductDto> postOutbox(@RequestHeader(value = "X-Inject-Failure", required = false) String inject,
                                                 @Valid @RequestBody ProductDto in) {
        injectFailures(inject);
        return ResponseEntity.ok(outboxSvc.save(in));
    }

    @PostMapping("/cdc/products")
    public ResponseEntity<ProductDto> postCdc(@Valid @RequestBody ProductDto in) {
        // CDC path doesn't write to ES from app code, so failure injection here doesn't model anything realistic.
        return ResponseEntity.ok(cdcSvc.save(in));
    }

    @DeleteMapping("/outbox/products/{id}")
    public ResponseEntity<Void> deleteOutbox(@PathVariable String id) {
        outboxSvc.delete(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/cdc/products/{id}")
    public ResponseEntity<Void> deleteCdc(@PathVariable String id) {
        cdcSvc.delete(id);
        return ResponseEntity.noContent().build();
    }

    private void injectFailures(String header) {
        if (header == null) return;
        for (String token : header.split(",")) {
            switch (token.trim()) {
                case "es-fail"     -> failures.inject(Target.ES, 1);
                case "kafka-fail"  -> failures.inject(Target.KAFKA, 1);
                case "db-rollback" -> failures.inject(Target.DB, 1);
                default -> { /* unknown — ignore */ }
            }
        }
    }
}
