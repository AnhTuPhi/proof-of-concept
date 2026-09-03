package com.poc.model;

import java.time.Instant;

/**
 * Sample row used by the cursor-pagination demo.
 * Ordered by (createdAt, id) so ties at the same instant remain deterministic.
 */
public record Item(long id, String name, Instant createdAt) {}
