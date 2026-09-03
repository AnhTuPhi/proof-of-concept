package com.demo.resilience.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Intercepts mutating requests that carry an `Idempotency-Key` header.
 * Replays the cached response on duplicates. If the body differs, returns 409
 * — the most surprising bug is when a key gets reused with a different payload
 * and we accidentally double-charge.
 */
@Component
@Order(10)
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final String HEADER = "Idempotency-Key";

    private final IdempotencyStore store;

    public IdempotencyFilter(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        String requestHash = sha256(body);

        var hit = store.get(key);
        if (hit.isPresent()) {
            var entry = hit.get();
            if (!entry.requestHash().equals(requestHash)) {
                log.warn("idempotency key {} reused with different body", key);
                response.setStatus(409);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"idempotency key reused with different payload\"}");
                return;
            }
            log.info("idempotency replay for key {}", key);
            response.setStatus(entry.status());
            response.setContentType("application/json");
            response.setHeader("Idempotency-Replayed", "true");
            response.getWriter().write(entry.body());
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        HttpServletRequest cached = new CachedBodyRequest(request, body);
        chain.doFilter(cached, wrapped);

        byte[] responseBytes = wrapped.getContentAsByteArray();
        String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
        if (wrapped.getStatus() >= 200 && wrapped.getStatus() < 300) {
            store.put(key, wrapped.getStatus(), responseBody, requestHash);
        }
        wrapped.copyBodyToResponse();
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
