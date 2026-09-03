package com.claude.kafka.common.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.SneakyThrows;

/**
 * Single configured ObjectMapper - avoids each module spinning up its own
 * with inconsistent settings (the usual cause of "works locally, dies in prod"
 * Instant serialization bugs).
 */
public final class JsonCodec {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonCodec() {}

    @SneakyThrows
    public static String toJson(Object o) {
        return MAPPER.writeValueAsString(o);
    }

    @SneakyThrows
    public static <T> T fromJson(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }
}
