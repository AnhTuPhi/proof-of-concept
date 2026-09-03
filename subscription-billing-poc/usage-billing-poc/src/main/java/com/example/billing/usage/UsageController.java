package com.example.billing.usage;

import com.example.billing.common.BillingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class UsageController {

    private final IngestionService ingestion;
    private final RollupService rollup;
    private final BillingService billing;
    private final ReconciliationService reconciliation;

    public UsageController(IngestionService ingestion, RollupService rollup,
                           BillingService billing, ReconciliationService reconciliation) {
        this.ingestion = ingestion;
        this.rollup = rollup;
        this.billing = billing;
        this.reconciliation = reconciliation;
    }

    public record IngestRequest(String eventId, String customerId, UsageMetric metric,
                                BigDecimal quantity, Instant occurredAt) {
    }

    @GetMapping("/pricing/tiers")
    public List<PricingTier> tiers() {
        return billing.allTiers();
    }

    @GetMapping("/usage/recent")
    public List<UsageEvent> recentEvents(@RequestParam(defaultValue = "50") int limit) {
        return billing.recentEvents(limit);
    }

    @GetMapping("/usage/rollups")
    public List<DailyRollup> rollups(@RequestParam String customerId,
                                     @RequestParam LocalDate periodStart,
                                     @RequestParam LocalDate periodEnd) {
        return billing.rollupsInRange(customerId, periodStart, periodEnd);
    }

    @PostMapping("/usage/events")
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody IngestRequest req) {
        IngestionService.IngestionResult result = ingestion.ingest(
                req.eventId(), req.customerId(), req.metric(), req.quantity(),
                req.occurredAt() == null ? Instant.now() : req.occurredAt());
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(Map.of(
                "id", result.event().getId(),
                "eventId", result.event().getEventId(),
                "duplicate", result.duplicate()));
    }

    @PostMapping("/usage/rollup")
    public List<DailyRollup> rollup(@RequestParam String customerId, @RequestParam LocalDate day) {
        return rollup.rollup(customerId, day);
    }

    @PostMapping("/billing/invoices")
    public Invoice generate(@RequestParam String customerId,
                            @RequestParam LocalDate periodStart,
                            @RequestParam LocalDate periodEnd) {
        return billing.generateInvoice(customerId, periodStart, periodEnd);
    }

    @GetMapping("/billing/invoices/{customerId}")
    public List<Invoice> invoices(@PathVariable String customerId) {
        return billing.invoicesFor(customerId);
    }

    @GetMapping("/billing/reconcile")
    public ReconciliationService.ReconciliationReport reconcile(@RequestParam String customerId,
                                                                @RequestParam LocalDate periodStart,
                                                                @RequestParam LocalDate periodEnd) {
        return reconciliation.reconcile(customerId, periodStart, periodEnd);
    }

    @ExceptionHandler(BillingException.class)
    public ResponseEntity<Map<String, String>> handleBilling(BillingException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleArg(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
