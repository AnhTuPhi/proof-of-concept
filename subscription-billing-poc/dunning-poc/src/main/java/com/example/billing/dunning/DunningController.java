package com.example.billing.dunning;

import com.example.billing.common.BillingException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DunningController {

    private final DunningService service;
    private final NotificationGateway notifier;

    public DunningController(DunningService service, NotificationGateway notifier) {
        this.service = service;
        this.notifier = notifier;
    }

    public record UpdatePaymentRequest(String brand, String last4, LocalDate expiresOn, LocalDate today) {
    }

    @GetMapping("/subscriptions")
    public List<Subscription> listSubscriptions() {
        return service.allSubscriptions();
    }

    @GetMapping("/payment-methods/{id}")
    public PaymentMethod paymentMethod(@PathVariable Long id) {
        return service.paymentMethod(id);
    }

    @PostMapping("/subscriptions/{id}/renew")
    public RenewalAttempt renew(@PathVariable Long id,
                                @RequestParam(required = false) LocalDate today) {
        LocalDate effective = today == null ? LocalDate.now() : today;
        return service.attemptInitialRenewal(id, effective);
    }

    @PostMapping("/subscriptions/{id}/payment-method")
    public Subscription updatePayment(@PathVariable Long id, @RequestBody UpdatePaymentRequest req) {
        LocalDate today = req.today() == null ? LocalDate.now() : req.today();
        return service.updatePaymentMethod(id, req.brand(), req.last4(), req.expiresOn(), today);
    }

    @PostMapping("/dunning/tick")
    public DunningService.ProcessSummary tick(@RequestParam(required = false) LocalDate today) {
        return service.tick(today == null ? LocalDate.now() : today);
    }

    @GetMapping("/subscriptions/{id}/dunning-events")
    public List<DunningEvent> events(@PathVariable Long id) {
        return service.timeline(id);
    }

    @GetMapping("/subscriptions/{id}/attempts")
    public List<RenewalAttempt> attempts(@PathVariable Long id) {
        return service.attempts(id);
    }

    @GetMapping("/notifications")
    public List<NotificationGateway.SentNotification> notifications() {
        return notifier.all();
    }

    @ExceptionHandler(BillingException.class)
    public ResponseEntity<Map<String, String>> handleBilling(BillingException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
