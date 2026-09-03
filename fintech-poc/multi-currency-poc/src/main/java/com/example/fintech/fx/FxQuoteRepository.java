package com.example.fintech.fx;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FxQuoteRepository extends JpaRepository<FxQuote, String> {
}
