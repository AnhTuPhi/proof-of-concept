package com.example.fintech.wallet;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    List<LedgerEntry> findByWalletId(String walletId);
    long countByWalletId(String walletId);
}
