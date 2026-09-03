package com.example.webapipoc.etag;

import java.math.BigDecimal;
import java.time.Instant;

public record Product(
    Long id,
    String code,
    String name,
    BigDecimal price,
    int stock,
    int version,           // optimistic concurrency token
    Instant updatedAt
) {}
