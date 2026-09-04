package com.vndirect.kstreams.topology;

import com.vndirect.kstreams.model.EnrichedOrder;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.KStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Composes all three topologies into the shared {@link StreamsBuilder}
 * that Spring's @EnableKafkaStreams injects. Order matters: enrichment
 * runs first so windowed aggregations consume the enriched stream directly,
 * avoiding a re-read from the enriched-orders topic.
 */
@Component
public class StreamsTopologyBuilder {

    private static final Logger log = LoggerFactory.getLogger(StreamsTopologyBuilder.class);

    private final OrderEnrichmentTopology enrichment;
    private final WindowedAggregationsTopology aggregations;
    private final OrderPaymentJoinTopology joins;

    public StreamsTopologyBuilder(OrderEnrichmentTopology enrichment,
                                  WindowedAggregationsTopology aggregations,
                                  OrderPaymentJoinTopology joins) {
        this.enrichment = enrichment;
        this.aggregations = aggregations;
        this.joins = joins;
    }

    @Autowired
    public void build(StreamsBuilder builder) {
        log.info("Wiring topologies: enrichment → aggregations + payment-join");
        KStream<String, EnrichedOrder> enriched = enrichment.build(builder);
        aggregations.build(enriched);
        joins.build(builder);
    }
}
