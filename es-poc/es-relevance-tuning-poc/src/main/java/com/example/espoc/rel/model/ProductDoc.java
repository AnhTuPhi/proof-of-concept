package com.example.espoc.rel.model;

public record ProductDoc(String id, String sku, String name, String brand, String description,
                         long popularity, long priceCents) {}
