package com.claude.kafka.streams.joins;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration;
import org.springframework.kafka.config.KafkaStreamsConfiguration;

import java.util.HashMap;
import java.util.Map;

@SpringBootApplication(scanBasePackages = {
        "com.claude.kafka.streams.joins",
        "com.claude.kafka.common"
})
@EnableKafkaStreams
public class JoinsApp {
    public static void main(String[] args) {
        SpringApplication.run(JoinsApp.class, args);
    }

    @Bean(name = KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
    public KafkaStreamsConfiguration kStreamsConfig(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        Map<String, Object> p = new HashMap<>();
        p.put(StreamsConfig.APPLICATION_ID_CONFIG, "clickstream-joins");
        p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        p.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        p.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
        p.put(StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG, 1);
        return new KafkaStreamsConfiguration(p);
    }
}
