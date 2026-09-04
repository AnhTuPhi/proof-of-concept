package com.example.espoc.common.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Opaque cursor encoding for search_after pagination.
 * Cursor is base64(JSON of the sort values). Clients treat it as opaque.
 */
public final class Cursor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Cursor() {}

    public static String encode(List<Object> sortValues) {
        try {
            byte[] json = MAPPER.writeValueAsBytes(sortValues);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JsonProcessingException e) {
            throw ApiException.internal("CURSOR_ENCODE_FAILED", "Could not encode cursor", e);
        }
    }

    public static List<Object> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return List.of();
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor.getBytes(StandardCharsets.US_ASCII));
            return MAPPER.readValue(json, MAPPER.getTypeFactory().constructCollectionType(List.class, Object.class));
        } catch (Exception e) {
            throw ApiException.badRequest("CURSOR_INVALID", "Cursor could not be decoded");
        }
    }
}
