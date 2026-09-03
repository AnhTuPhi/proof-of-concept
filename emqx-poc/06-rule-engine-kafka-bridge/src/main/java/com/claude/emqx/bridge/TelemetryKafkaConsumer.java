package com.claude.emqx.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Verifies the EMQX -> Kafka bridge by consuming the destination topic.
 * Counts can be queried via {@code GET /verify/kafka-count}.
 */
@Service
public class TelemetryKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryKafkaConsumer.class);
    private final AtomicLong consumed = new AtomicLong();

    @KafkaListener(topics = "iot.telemetry", groupId = "poc-06-verifier",
            properties = {"auto.offset.reset=earliest"})
    public void onMessage(String value) {
        long n = consumed.incrementAndGet();
        if (n % 100 == 0) log.info("Consumed {} from iot.telemetry", n);
    }

    public long count() { return consumed.get(); }
}
