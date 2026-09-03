package com.example.fintech.fx;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FxPnlEntryRepository extends JpaRepository<FxPnlEntry, Long> {
    List<FxPnlEntry> findByReferenceId(String referenceId);
}
