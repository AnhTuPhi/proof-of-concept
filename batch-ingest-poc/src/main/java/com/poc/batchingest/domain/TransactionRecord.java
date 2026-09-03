package com.poc.batchingest.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Canonical in-flight record used across all ingest sources (CSV, Parquet, DB).
 * Field-level bean validation runs in {@link com.poc.batchingest.batch.processor.ValidatingTransactionProcessor}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRecord {

    @NotBlank
    private String transactionId;

    @NotBlank
    private String accountId;

    @NotBlank
    private String symbol;

    @Pattern(regexp = "BUY|SELL", message = "side must be BUY or SELL")
    private String side;

    @NotNull
    @DecimalMin(value = "0.0001", message = "quantity must be positive")
    private BigDecimal quantity;

    @NotNull
    @DecimalMin(value = "0.0001", message = "price must be positive")
    private BigDecimal price;

    @NotNull
    private LocalDateTime tradeTs;

    /** Origin tag, set by the reader stage: CSV, PARQUET, DB. */
    private String source;
}
