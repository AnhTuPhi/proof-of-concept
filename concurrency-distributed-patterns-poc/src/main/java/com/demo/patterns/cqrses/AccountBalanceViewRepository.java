package com.demo.patterns.cqrses;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountBalanceViewRepository extends JpaRepository<AccountBalanceView, String> {
}
