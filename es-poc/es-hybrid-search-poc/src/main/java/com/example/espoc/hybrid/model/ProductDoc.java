package com.example.espoc.hybrid.model;

public record ProductDoc(String id, String name, String description, String category,
                         long priceCents, float[] embedding) {}
