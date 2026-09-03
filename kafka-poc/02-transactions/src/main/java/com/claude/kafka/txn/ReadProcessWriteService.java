package com.claude.kafka.txn;

import com.claude.kafka.common.consumer.SafeConsumerProps;
import com.claude.kafka.common.event.DomainEvent;
import com.claude.kafka.common.producer.SafeProducerProps;
import com.claude.kafka.common.topic.Topics;
import com.claude.kafka.common.util.JsonCodec;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The canonical Kafka exactly-once read-process-write loop.
 * <p>
 * Reads from {@code orders.placed.v1}, transforms each event into both a
 * {@code orders.paid.v1} record AND a {@code shipping.requested.v1} record,
 * then atomically:
 * <ul>
 *   <li>commits both outputs to Kafka,</li>
 *   <li>and commits the input consumer offset via
 *       {@link KafkaProducer#sendOffsetsToTransaction}.</li>
 * </ul>
 * If <em>any</em> step fails, the transaction aborts, the input offset is
 * never committed, and a downstream {@code read_committed} consumer never sees
 * the half-written outputs. That's the actual exactly-once-semantics guarantee.
 * <p>
 * Production gotcha: a transactional consumer must <strong>not</strong> also
 * have {@code enable.auto.commit=true} — the offset commit only counts if it
 * goes through {@code sendOffsetsToTransaction}.
 */
@Slf4j
@Service
public class ReadProcessWriteService implements Runnable {

    private static final String CONSUMER_GROUP = "txn-read-process-write";
    private static final String TXN_ID_PREFIX = "txn-rpw-";

    private final String bootstrap;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread worker;
    private KafkaConsumer<String, String> consumer;
    private KafkaProducer<String, String> producer;

    public ReadProcessWriteService(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        this.bootstrap = bootstrap;
    }

    @PostConstruct
    public void start() {
        worker = new Thread(this, "txn-rpw");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (worker != null) worker.interrupt();
    }

    @Override
    public void run() {
        Map<String, Object> cProps = new HashMap<>(SafeConsumerProps.base(
                bootstrap, CONSUMER_GROUP, "txn-rpw-consumer"));
        cProps.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        // The transactional ID must be stable per producer instance so that
        // crashes are recovered via "zombie fencing". In K8s, derive from the
        // pod ordinal (StatefulSet) or hostname.
        String txnId = TXN_ID_PREFIX + System.getenv().getOrDefault("HOSTNAME", "local");
        consumer = new KafkaConsumer<>(cProps);
        producer = new KafkaProducer<>(SafeProducerProps.transactional(
                bootstrap, "txn-rpw-producer", txnId));

        producer.initTransactions();
        consumer.subscribe(List.of(Topics.ORDERS_PLACED));
        log.info("Read-process-write loop started with txn.id={}", txnId);

        while (running.get()) {
            try {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                if (records.isEmpty()) continue;

                producer.beginTransaction();
                Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();

                for (ConsumerRecord<String, String> in : records) {
                    DomainEvent<?> event = JsonCodec.fromJson(in.value(), DomainEvent.class);

                    // Atomic fan-out: a single input becomes outputs on two topics
                    ProducerRecord<String, String> paidOut = new ProducerRecord<>(
                            Topics.ORDERS_PAID, in.key(),
                            JsonCodec.toJson(DomainEvent.of(
                                    "OrderPaid", "Order", event.getAggregateId(),
                                    Map.of("paid", true))));
                    ProducerRecord<String, String> shipOut = new ProducerRecord<>(
                            Topics.SHIPPING_REQUESTED, in.key(),
                            JsonCodec.toJson(DomainEvent.of(
                                    "ShippingRequested", "Order", event.getAggregateId(),
                                    Map.of("address", "TBD"))));

                    producer.send(paidOut);
                    producer.send(shipOut);

                    offsets.put(
                            new TopicPartition(in.topic(), in.partition()),
                            new OffsetAndMetadata(in.offset() + 1));
                }

                // The offset commit is part of the same transaction as the
                // produces. This is what makes the loop exactly-once.
                producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
                producer.commitTransaction();

                log.debug("Committed batch of {} records", records.count());
            } catch (ProducerFencedException fenced) {
                // Another instance with the same transactional.id took over.
                // Shut down cleanly - do NOT try to recover this producer.
                log.warn("Producer fenced - another instance is active. Shutting down.");
                running.set(false);
            } catch (Exception ex) {
                log.error("Aborting transaction due to: {}", ex.getMessage(), ex);
                try {
                    producer.abortTransaction();
                } catch (Exception abortEx) {
                    log.error("Abort failed: {}", abortEx.getMessage());
                }
            }
        }

        try { producer.close(); } catch (Exception ignored) {}
        try { consumer.close(); } catch (Exception ignored) {}
        log.info("Read-process-write loop stopped");
    }
}
