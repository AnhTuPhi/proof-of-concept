package com.example.billing.dunning;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

@Component
public class DataSeeder implements CommandLineRunner {

    private final PaymentMethodRepository pmRepo;
    private final SubscriptionRepository subRepo;

    public DataSeeder(PaymentMethodRepository pmRepo, SubscriptionRepository subRepo) {
        this.pmRepo = pmRepo;
        this.subRepo = subRepo;
    }

    @Override
    public void run(String... args) {
        if (subRepo.count() > 0) {
            return;
        }
        PaymentMethod healthyCard = pmRepo.save(
                new PaymentMethod("cust-healthy", "VISA", "4242", LocalDate.of(2030, 12, 31)));
        PaymentMethod badCard = pmRepo.save(
                new PaymentMethod("cust-bad", "VISA", "0002", LocalDate.of(2030, 12, 31)));
        badCard.forceDecline("insufficient_funds");
        pmRepo.save(badCard);

        subRepo.save(new Subscription("cust-healthy", "Pro", new BigDecimal("30.00"),
                healthyCard.getId(), LocalDate.now()));
        subRepo.save(new Subscription("cust-bad", "Pro", new BigDecimal("30.00"),
                badCard.getId(), LocalDate.now()));
    }
}
