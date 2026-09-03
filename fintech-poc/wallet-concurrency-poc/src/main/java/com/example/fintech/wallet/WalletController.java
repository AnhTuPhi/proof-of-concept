package com.example.fintech.wallet;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService service;

    public WalletController(WalletService service) {
        this.service = service;
    }

    @PostMapping("/{id}/debit")
    public Map<String, Object> debit(
            @PathVariable("id") String id,
            @RequestParam("amount") BigDecimal amount,
            @RequestParam(name = "strategy", defaultValue = "CONDITIONAL_UPDATE") String strategy) {
        BigDecimal newBalance = service.debit(id, amount, strategy);
        return Map.of("walletId", id, "newBalance", newBalance, "strategy", strategy);
    }

    @GetMapping("/{id}")
    public Wallet get(@PathVariable("id") String id) {
        return service.get(id);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<String> insufficient(InsufficientFundsException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(e.getMessage());
    }
}
