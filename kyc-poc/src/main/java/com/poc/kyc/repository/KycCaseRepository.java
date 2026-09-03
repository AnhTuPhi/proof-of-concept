package com.poc.kyc.repository;

import com.poc.kyc.model.DlqEntry;
import com.poc.kyc.model.KycCase;
import com.poc.kyc.model.KycState;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory repository. In production this would be JPA + Oracle (per CLAUDE.md
 * project conventions) with optimistic locking on state transitions.
 */
@Repository
public class KycCaseRepository {

    private final ConcurrentHashMap<String, KycCase> cases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DlqEntry> dlq = new ConcurrentHashMap<>();

    public KycCase save(KycCase kycCase) {
        cases.put(kycCase.getId(), kycCase);
        return kycCase;
    }

    public Optional<KycCase> findById(String id) {
        return Optional.ofNullable(cases.get(id));
    }

    public Collection<KycCase> findAll() {
        return cases.values();
    }

    public List<KycCase> findByState(KycState state) {
        return cases.values().stream()
                .filter(c -> c.getState() == state)
                .toList();
    }

    public DlqEntry saveDlqEntry(DlqEntry entry) {
        dlq.put(entry.getId(), entry);
        return entry;
    }

    public Optional<DlqEntry> findDlqEntry(String id) {
        return Optional.ofNullable(dlq.get(id));
    }

    public Collection<DlqEntry> findAllDlqEntries() {
        return dlq.values();
    }

    public long countByState(KycState state) {
        return cases.values().stream().filter(c -> c.getState() == state).count();
    }

    public long countDlq() {
        return dlq.values().stream().filter(e -> !e.isReplayed()).count();
    }
}
