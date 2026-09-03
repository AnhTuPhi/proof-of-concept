package com.example.billing.proration;

import org.springframework.data.jpa.repository.JpaRepository;

interface PlanRepository extends JpaRepository<Plan, String> {
}

interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
}

interface InvoiceRepository extends JpaRepository<Invoice, Long> {
}

interface CustomerCreditRepository extends JpaRepository<CustomerCredit, String> {
}
