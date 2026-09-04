package com.vndirect.kstreams.error;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.streams.errors.ProductionExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Production-side handler. Drops only well-understood, non-retriable errors
 * (e.g. RecordTooLargeException) and fails fast on anything else so partial
 * data is never silently dropped.
 */
public class LoggingProductionExceptionHandler implements ProductionExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingProductionExceptionHandler.class);

    @Override
    public ProductionExceptionHandlerResponse handle(ProducerRecord<byte[], byte[]> record,
                                                     Exception exception) {
        if (exception instanceof RecordTooLargeException) {
            log.error("Dropping oversized record on topic {} (offset partition {}): {}",
                    record.topic(), record.partition(), exception.getMessage());
            return ProductionExceptionHandlerResponse.CONTINUE;
        }
        log.error("Unrecoverable production error on topic {} — failing stream thread",
                record.topic(), exception);
        return ProductionExceptionHandlerResponse.FAIL;
    }

    @Override
    public void configure(Map<String, ?> configs) {
        // no-op
    }
}
