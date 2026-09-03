package com.poc.batchingest.batch.processor;

import com.poc.batchingest.domain.TransactionRecord;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs Bean Validation against every record. Violations are surfaced as {@link ConstraintViolationException},
 * which the step's skip policy is configured to tolerate up to {@code ingest.skip-limit} occurrences.
 *
 * <p>Returning {@code null} from {@link ItemProcessor#process} filters the item out without counting
 * it as an error — handy for soft duplicates if the writer is already idempotent.
 */
@Component
public class ValidatingTransactionProcessor implements ItemProcessor<TransactionRecord, TransactionRecord> {

    private final Validator validator;

    public ValidatingTransactionProcessor(Validator validator) {
        this.validator = validator;
    }

    @Override
    public TransactionRecord process(TransactionRecord item) {
        Set<ConstraintViolation<TransactionRecord>> violations = validator.validate(item);
        if (!violations.isEmpty()) {
            String msg = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining("; "));
            throw new ConstraintViolationException("invalid record [" + item.getTransactionId() + "]: " + msg, violations);
        }
        return item;
    }
}
