package com.example.espoc.sync.strategy.outbox;

import com.example.espoc.sync.es.ProductEsIndexer;
import com.example.espoc.sync.model.dto.ProductDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Reads outbox events from Kafka and applies them to ES.
 *
 * <p>Important: we only commit the Kafka offset (via {@code ack.acknowledge()}) <i>after</i> the ES write
 * succeeds. If ES write throws, the offset stays put → message will be redelivered on the next poll.
 *
 * <p>Combined with external versioning in the indexer, duplicate deliveries are safe.
 */
@Component
public class OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(OutboxConsumer.class);

    private final ProductEsIndexer indexer;
    private final ObjectMapper mapper;

    public OutboxConsumer(ProductEsIndexer indexer, ObjectMapper mapper) {
        this.indexer = indexer;
        this.mapper = mapper;
    }

    @KafkaListener(topics = OutboxPoller.TOPIC, groupId = "db-to-es-sync-outbox-consumer")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        ProductDto dto = mapper.readValue(record.value(), ProductDto.class);
        // ES write — if this throws, offset is NOT acked, message is redelivered.
        indexer.index(ProductEsIndexer.IDX_OUTBOX, dto);
        ack.acknowledge();
        log.debug("Consumed event for aggregate {} from partition {}/{}",
                record.key(), record.partition(), record.offset());
    }
}
