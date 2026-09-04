package com.vndirect.kstreams.serdes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Generic JSON Serde backed by Jackson. Tolerates null values (returns null bytes),
 * and surfaces parse failures as {@link SerializationException} so the configured
 * {@link org.apache.kafka.streams.errors.DeserializationExceptionHandler} can route
 * the bad record to the DLQ instead of crashing the stream thread.
 */
public class JsonSerde<T> implements Serde<T> {

    private final ObjectMapper mapper;
    private final TypeReference<T> typeRef;
    private final Class<T> clazz;

    public JsonSerde(ObjectMapper mapper, Class<T> clazz) {
        this.mapper = mapper;
        this.clazz = clazz;
        this.typeRef = null;
    }

    public JsonSerde(ObjectMapper mapper, TypeReference<T> typeRef) {
        this.mapper = mapper;
        this.typeRef = typeRef;
        this.clazz = null;
    }

    @Override
    public Serializer<T> serializer() {
        return (topic, data) -> {
            if (data == null) return null;
            try {
                return mapper.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new SerializationException("Failed to serialize value for topic " + topic, e);
            }
        };
    }

    @Override
    public Deserializer<T> deserializer() {
        return (topic, bytes) -> {
            if (bytes == null || bytes.length == 0) return null;
            try {
                if (clazz != null) {
                    return mapper.readValue(bytes, clazz);
                }
                return mapper.readValue(bytes, typeRef);
            } catch (IOException e) {
                String preview = new String(bytes, StandardCharsets.UTF_8);
                if (preview.length() > 200) {
                    preview = preview.substring(0, 200) + "...";
                }
                throw new SerializationException(
                        "Failed to deserialize JSON for topic " + topic + ": " + preview, e);
            }
        };
    }
}
