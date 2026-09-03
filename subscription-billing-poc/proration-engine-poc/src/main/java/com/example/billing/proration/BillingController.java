package com.example.billing.proration;

import com.example.billing.common.BillingException;
import com.example.billing.common.Money;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class BillingController {

    private final SubscriptionService service;

    public BillingController(SubscriptionService service) {
        this.service = service;
    }

    public record CreateSubscriptionRequest(String customerId, String planId, LocalDate today) {
    }

    public record ChangePlanRequest(String newPlanId, ChangeStrategy strategy, LocalDate today) {
    }

    public record CancelRequest(LocalDate today) {
    }

    public record InvoiceView(Long id, Long subscriptionId, String customerId, String currency,
                              String status, BigDecimal total, List<LineView> lineItems) {
        static InvoiceView from(Invoice i) {
            List<LineView> lines = i.getLineItems().stream()
                    .map(li -> new LineView(li.getDescription(), li.getAmount(),
                            li.getType().name(), li.getPlanId(),
                            li.getPeriodStart(), li.getPeriodEnd()))
                    .toList();
            return new InvoiceView(i.getId(), i.getSubscriptionId(), i.getCustomerId(),
                    i.getCurrency(), i.getStatus().name(), i.total().amount(), lines);
        }
    }

    public record LineView(String description, BigDecimal amount, String type, String planId,
                           LocalDate periodStart, LocalDate periodEnd) {
    }

    @PostMapping("/subscriptions")
    public ResponseEntity<Subscription> create(@RequestBody CreateSubscriptionRequest req) {
        LocalDate today = req.today() == null ? LocalDate.now() : req.today();
        Subscription s = service.create(req.customerId(), req.planId(), today);
        return ResponseEntity.status(HttpStatus.CREATED).body(s);
    }

    @PostMapping("/subscriptions/{id}/change-plan")
    public InvoiceView change(@PathVariable Long id, @RequestBody ChangePlanRequest req) {
        LocalDate today = req.today() == null ? LocalDate.now() : req.today();
        Invoice invoice = service.changePlan(id, req.newPlanId(), req.strategy(), today);
        return InvoiceView.from(invoice);
    }

    @PostMapping("/subscriptions/{id}/cancel")
    public InvoiceView cancel(@PathVariable Long id, @RequestBody(required = false) CancelRequest req) {
        LocalDate today = (req == null || req.today() == null) ? LocalDate.now() : req.today();
        Invoice invoice = service.cancel(id, today);
        return InvoiceView.from(invoice);
    }

    @GetMapping("/subscriptions")
    public List<Subscription> listSubscriptions() {
        return service.allSubscriptions();
    }

    @GetMapping("/subscriptions/{id}/invoices")
    public List<InvoiceView> invoices(@PathVariable Long id) {
        return service.invoicesFor(id).stream().map(InvoiceView::from).toList();
    }

    @GetMapping("/customers/{id}/credit-balance")
    public Map<String, Object> creditBalance(@PathVariable("id") String customerId) {
        Money m = service.creditBalance(customerId);
        return Map.of("customerId", customerId, "currency", m.currencyCode(), "balance", m.amount());
    }

    @GetMapping("/plans")
    public List<Plan> plans(@RequestParam(required = false) String filter) {
        return service.plansForListing();
    }

    @ExceptionHandler(BillingException.class)
    public ResponseEntity<Map<String, String>> handleBilling(BillingException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
