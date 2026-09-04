package com.example.espoc.hybrid.embed;

/** Replace with an OpenAI / Cohere / local-model implementation for real use. */
public interface EmbeddingClient {
    float[] embed(String text);
    int dims();
}
