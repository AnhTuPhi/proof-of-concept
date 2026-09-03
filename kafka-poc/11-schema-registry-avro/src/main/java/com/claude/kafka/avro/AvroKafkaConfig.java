package com.claude.kafka.avro;

import com.claude.kafka.common.consumer.SafeConsumerProps;
import com.claude.kafka.common.producer.SafeProducerProps;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;

import java.util.Map;

/**
 * Avro + Schema Registry wiring. Two non-obvious settings:
 * <ul>
 *   <li>{@code auto.register.schemas=false} for producers in production —
 *       schemas should be registered via CI gate, not by accident on first
 *       deploy. Auto-register on local/dev only.</li>
 *   <li>{@code specific.avro.reader=true} so consumers deserialize directly
 *       into the generated class instead of GenericRecord.</li>
 * </ul>
 */
@Configuration
public class AvroKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrap;
    @Value("${app.schema-registry.url}")
    private String schemaRegistryUrl;
    @Value("${app.schema-registry.auto-register:true}")
    private boolean autoRegister;

    @Bean
    public ProducerFactory<String, Object> avroProducerFactory() {
        Map<String, Object> p = SafeProducerProps.base(bootstrap, "avro-producer");
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        p.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        p.put(AbstractKafkaSchemaSerDeConfig.AUTO_REGISTER_SCHEMAS, autoRegister);
        p.put(AbstractKafkaSchemaSerDeConfig.USE_LATEST_VERSION, true);
        return new DefaultKafkaProducerFactory<>(p);
    }

    @Bean
    public KafkaTemplate<String, Object> avroKafkaTemplate(ProducerFactory<String, Object> pf) {
        return new KafkaTemplate<>(pf);
    }

    @Bean
    public ConsumerFactory<String, Object> avroConsumerFactory() {
        Map<String, Object> p = SafeConsumerProps.base(bootstrap, "avro-consumer-group", "avro-consumer");
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        p.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        p.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        return new DefaultKafkaConsumerFactory<>(p);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> avroListenerFactory() {
        var f = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        f.setConsumerFactory(avroConsumerFactory());
        return f;
    }
}
