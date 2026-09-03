package com.claude.kafka.saga;

import com.claude.kafka.common.producer.SafeProducerProps;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication(scanBasePackages = {
        "com.claude.kafka.saga",
        "com.claude.kafka.common"
})
@EnableKafka
@EnableTransactionManagement
public class SagaApp {
    public static void main(String[] args) {
        SpringApplication.run(SagaApp.class, args);
    }

    @Bean
    public ProducerFactory<String, String> producerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        return new DefaultKafkaProducerFactory<>(SafeProducerProps.base(bootstrap, "saga-producer"));
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> pf) {
        return new KafkaTemplate<>(pf);
    }
}
