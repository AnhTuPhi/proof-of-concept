package com.example.fintech.recon;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProviderTxnRepository extends JpaRepository<ProviderTxn, String> {
    List<ProviderTxn> findByMatched(boolean matched);
    Optional<ProviderTxn> findByProviderRef(String providerRef);
}
