package com.example.fintech.recon;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InternalTxnRepository extends JpaRepository<InternalTxn, String> {
    List<InternalTxn> findByMatched(boolean matched);
    Optional<InternalTxn> findByProviderRef(String providerRef);
}
