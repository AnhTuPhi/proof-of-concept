package com.example.billing.proration;

import com.example.billing.common.Money;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataSeeder implements CommandLineRunner {

    private final PlanRepository planRepo;

    public DataSeeder(PlanRepository planRepo) {
        this.planRepo = planRepo;
    }

    @Override
    public void run(String... args) {
        if (planRepo.count() > 0) {
            return;
        }
        planRepo.save(new Plan("basic", "Basic", Money.usd("10.00")));
        planRepo.save(new Plan("pro", "Pro", Money.usd("30.00")));
        planRepo.save(new Plan("enterprise", "Enterprise", Money.usd("100.00")));
    }
}
