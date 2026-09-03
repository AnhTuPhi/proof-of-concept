package com.example.fintech.recon;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BreakRepository extends JpaRepository<ReconciliationBreak, Long> {
    List<ReconciliationBreak> findByStatus(ReconciliationBreak.BreakStatus status);
}
