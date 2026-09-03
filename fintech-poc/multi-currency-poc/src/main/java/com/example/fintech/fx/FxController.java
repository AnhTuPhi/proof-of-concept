package com.example.fintech.fx;

import com.example.fintech.common.Currency;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/fx")
public class FxController {

    private final FxService service;

    public FxController(FxService service) { this.service = service; }

    @PostMapping("/quotes")
    public FxQuote quote(@RequestParam Currency from, @RequestParam Currency to) {
        return service.quote(from, to);
    }

    @PostMapping("/payments")
    public FxPayment pay(@RequestParam String quoteId, @RequestParam BigDecimal amount) {
        return service.payAgainstQuote(quoteId, amount);
    }

    @PostMapping("/payments/{id}/refund")
    public FxService.RefundResult refund(@PathVariable("id") String id, @RequestParam BigDecimal amount) {
        return service.refundOriginalCurrency(id, amount);
    }
}
