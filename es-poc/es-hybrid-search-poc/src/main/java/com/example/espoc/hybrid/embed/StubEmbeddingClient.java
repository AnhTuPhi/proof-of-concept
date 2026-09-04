package com.example.espoc.hybrid.embed;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic hash-to-vector embedder. Not semantically meaningful — it's a stand-in so the kNN
 * code path works end-to-end. Replace with a real model in production.
 *
 * <p>The trick: split the input into bigrams, hash each, mix into a fixed-dim vector, L2-normalize.
 * Similar texts → similar (overlapping) bigrams → similar vectors.
 */
@Component
@ConditionalOnProperty(name = "app.embeddings.provider", havingValue = "stub", matchIfMissing = true)
public class StubEmbeddingClient implements EmbeddingClient {

    private final int dims;

    public StubEmbeddingClient(@Value("${app.hybrid.embedding-dims:384}") int dims) {
        this.dims = dims;
    }

    @Override public int dims() { return dims; }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dims];
        if (text == null || text.isBlank()) {
            v[0] = 1f; return v;          // unit vector along axis 0 for empty text
        }
        String lower = text.toLowerCase();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < lower.length() - 1; i++) {
                byte[] h = md.digest(lower.substring(i, i + 2).getBytes());
                for (int b = 0; b < h.length; b++) {
                    int bucket = ((h[b] & 0xFF) * 7919) % dims;
                    if (bucket < 0) bucket += dims;
                    v[bucket] += (b % 2 == 0 ? 1f : -1f);
                }
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        // L2 normalize so cosine similarity is well-behaved
        double norm = 0;
        for (float x : v) norm += x * x;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dims; i++) v[i] /= (float) norm;
        }
        return v;
    }
}
