package com.vndirect.kstreams.error;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.streams.errors.DeserializationExceptionHandler;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Routes records that fail deserialization to a DLQ topic, then signals
 * the stream to CONTINUE so a single poison-pill record cannot block the
 * partition. Original key, value, topic, partition, offset, and error
 * details are preserved as headers for downstream triage.
 */
public class DlqDeserializationExceptionHandler implements DeserializationExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DlqDeserializationExceptionHandler.class);

    static final String DLQ_TOPIC_CONFIG = "dlq.topic.name";
    static final String DLQ_BOOTSTRAP_CONFIG = "dlq.bootstrap.servers";

    private Producer<byte[], byte[]> dlqProducer;
    private String dlqTopic;

    @Override
    public DeserializationHandlerResponse handle(ProcessorContext context,
                                                 ConsumerRecord<byte[], byte[]> record,
                                                 Exception exception) {
        try {
            ProducerRecord<byte[], byte[]> dlqRecord =
                    new ProducerRecord<>(dlqTopic, null, record.key(), record.value());
            dlqRecord.headers().add(new RecordHeader("dlq.origin.topic",
                    record.topic().getBytes(StandardCharsets.UTF_8)));
            dlqRecord.headers().add(new RecordHeader("dlq.origin.partition",
                    String.valueOf(record.partition()).getBytes(StandardCharsets.UTF_8)));
            dlqRecord.headers().add(new RecordHeader("dlq.origin.offset",
                    String.valueOf(record.offset()).getBytes(StandardCharsets.UTF_8)));
            dlqRecord.headers().add(new RecordHeader("dlq.error.class",
                    exception.getClass().getName().getBytes(StandardCharsets.UTF_8)));
            dlqRecord.headers().add(new RecordHeader("dlq.error.message",
                    String.valueOf(exception.getMessage()).getBytes(StandardCharsets.UTF_8)));
            dlqProducer.send(dlqRecord);
            log.warn("Routed poison-pill record from {}-{}@{} to DLQ {}",
                    record.topic(), record.partition(), record.offset(), dlqTopic);
        } catch (Exception send) {
            log.error("Failed to publish to DLQ; failing fast to avoid silent data loss", send);
            return DeserializationHandlerResponse.FAIL;
        }
        return DeserializationHandlerResponse.CONTINUE;
    }

    @Override
    public void configure(Map<String, ?> configs) {
        this.dlqTopic = (String) configs.get(DLQ_TOPIC_CONFIG);
        String bootstrap = (String) configs.get(DLQ_BOOTSTRAP_CONFIG);
        if (dlqTopic == null || bootstrap == null) {
            throw new IllegalStateException(
                    "DLQ handler requires " + DLQ_TOPIC_CONFIG + " and " + DLQ_BOOTSTRAP_CONFIG);
        }
        Map<String, Object> producerConfig = new HashMap<>();
        producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
        producerConfig.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        producerConfig.put(ProducerConfig.CLIENT_ID_CONFIG, "streams-dlq-producer");
        this.dlqProducer = new KafkaProducer<>(producerConfig);
    }
}
