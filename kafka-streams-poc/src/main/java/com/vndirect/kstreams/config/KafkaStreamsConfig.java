package com.vndirect.kstreams.config;

import com.vndirect.kstreams.error.DlqDeserializationExceptionHandler;
import com.vndirect.kstreams.error.LoggingProductionExceptionHandler;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.config.KafkaStreamsConfiguration;
import org.springframework.kafka.config.StreamsBuilderFactoryBeanConfigurer;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME;

@Configuration
@EnableKafkaStreams
public class KafkaStreamsConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaStreamsConfig.class);

    private final AppProperties props;

    public KafkaStreamsConfig(AppProperties props) {
        this.props = props;
    }

    @Bean(name = DEFAULT_STREAMS_CONFIG_BEAN_NAME)
    public KafkaStreamsConfiguration kStreamsConfig() {
        Map<String, Object> cfg = new HashMap<>();
        AppProperties.Kafka k = props.getKafka();

        cfg.put(StreamsConfig.APPLICATION_ID_CONFIG, k.getApplicationId());
        cfg.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, k.getBootstrapServers());
        cfg.put(StreamsConfig.STATE_DIR_CONFIG, k.getStateDir());
        cfg.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        cfg.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        cfg.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, k.getNumStreamThreads());
        cfg.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, (int) k.getReplicationFactor());
        cfg.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, k.getCommitIntervalMs());
        cfg.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, k.getCacheMaxBytesBuffering());
        cfg.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, k.getProcessingGuarantee());

        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, k.getAutoOffsetReset());
        cfg.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, k.getMaxPollRecords());
        cfg.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, k.getRequestTimeoutMs());
        cfg.put(ConsumerConfig.RETRY_BACKOFF_MS_CONFIG, k.getRetryBackoffMs());

        cfg.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                DlqDeserializationExceptionHandler.class);
        cfg.put(StreamsConfig.DEFAULT_PRODUCTION_EXCEPTION_HANDLER_CLASS_CONFIG,
                LoggingProductionExceptionHandler.class);

        cfg.put(DlqDeserializationExceptionHandler.DLQ_TOPIC_CONFIG, props.getTopics().getDlq());
        cfg.put(DlqDeserializationExceptionHandler.DLQ_BOOTSTRAP_CONFIG, k.getBootstrapServers());

        cfg.put(StreamsConfig.RETRY_BACKOFF_MS_CONFIG, k.getRetryBackoffMs());

        return new KafkaStreamsConfiguration(cfg);
    }

    /**
     * Installs the uncaught-exception handler on the KafkaStreams instance.
     * Using REPLACE_THREAD keeps the app alive on transient processor failures
     * — fatal infra problems (broker gone, disk full) still propagate up.
     */
    @Bean
    public StreamsBuilderFactoryBeanConfigurer streamsCustomizer() {
        return factoryBean -> factoryBean.setKafkaStreamsCustomizer(streams ->
                streams.setUncaughtExceptionHandler(ex -> {
                    log.error("Uncaught streams exception — replacing thread", ex);
                    return StreamsUncaughtExceptionHandler
                            .StreamThreadExceptionResponse.REPLACE_THREAD;
                })
        );
    }
}
