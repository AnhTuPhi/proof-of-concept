package com.claude.emqx.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * One shared {@link ObjectMapper} so every POC produces identical JSON on the
 * wire. Useful when you're tail-ing topics with {@code mqttx sub --pretty}
 * across modules and want the format to be uniform.
 */
public final class Json {
    private Json() {}

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public static byte[] toBytes(Object o) {
        try { return MAPPER.writeValueAsBytes(o); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static <T> T fromBytes(byte[] bytes, Class<T> type) {
        try { return MAPPER.readValue(bytes, type); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
