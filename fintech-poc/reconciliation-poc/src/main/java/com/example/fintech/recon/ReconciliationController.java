package com.example.fintech.recon;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/recon")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @PostMapping("/run")
    public ReconciliationService.ReconciliationReport run() {
        return service.runDailyReconciliation();
    }
}
