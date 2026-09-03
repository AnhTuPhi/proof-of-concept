package com.example.webapipoc.etag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * ETag + If-None-Match demo.
 *
 *   GET /api/products              → list with strong ETag derived from {id, version} tuples
 *   GET /api/products/{id}         → strong ETag derived from SHA-256 of JSON body
 *   PUT /api/products/{id}         → If-Match precondition (optimistic concurrency)
 *
 * Client flow:
 *   1) First GET returns 200 + ETag header.
 *   2) Subsequent GETs send `If-None-Match: "<etag>"`.
 *   3) If the resource has not changed, server returns 304 Not Modified with NO body — saves bandwidth.
 *
 * Note: Spring also ships a `ShallowEtagHeaderFilter` that does this generically. We compute manually
 * so the demo is explicit, and so we can mix in version-based "strong" semantics.
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductStore store;
    private final ObjectMapper objectMapper;

    public ProductController(ProductStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<List<Product>> list(
        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        List<Product> all = store.findAll();
        // List ETag: hash of all (id,version) tuples — cheap and stable
        String etag = quote(sha256(all.stream()
            .map(p -> p.id() + ":" + p.version())
            .reduce("", (a, b) -> a + "|" + b)));
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePrivate())
                .build();
        }
        return ResponseEntity.ok()
            .eTag(etag)
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePrivate())
            .body(all);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> get(
        @PathVariable Long id,
        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        Product p = store.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String etag = computeEtag(p);
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePrivate())
                .build();
        }
        return ResponseEntity.ok()
            .eTag(etag)
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePrivate())
            .contentType(MediaType.APPLICATION_JSON)
            .body(p);
    }

    /**
     * PUT with If-Match — optimistic concurrency. If the client's ETag is stale (someone else updated
     * between their GET and PUT), respond 412 Precondition Failed so the client can re-fetch & retry.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(
        @PathVariable Long id,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
        @RequestBody UpdateRequest body
    ) {
        Product current = store.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (ifMatch == null) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED)
                .body("If-Match header is required for updates");
        }
        String currentEtag = computeEtag(current);
        if (!currentEtag.equals(ifMatch)) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .eTag(currentEtag)
                .body("ETag mismatch — resource was modified by someone else. Refetch and retry.");
        }
        Product updated = store.update(id, body.name(), body.price(), body.stock()).orElseThrow();
        return ResponseEntity.ok()
            .eTag(computeEtag(updated))
            .body(updated);
    }

    private String computeEtag(Product p) {
        try {
            String json = objectMapper.writeValueAsString(p);
            return quote(sha256(json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes());
            // 16 hex chars (8 bytes) is plenty for ETag entropy and keeps headers small
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String quote(String s) { return "\"" + s + "\""; }

    public record UpdateRequest(String name, BigDecimal price, Integer stock) {}
}
