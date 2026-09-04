package com.example.espoc.bulk.service;

import com.example.espoc.bulk.model.ProductDoc;
import com.example.espoc.common.id.IdGenerators;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/** Stateless synthetic-data factory — keeps benchmark code clean. */
@Component
public class ProductGenerator {

    public ProductDoc next(long index) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String id = IdGenerators.ulid();
        return new ProductDoc(
                id,
                "SKU-" + index,
                "Product " + index,
                "Lorem ipsum description for product " + index + " with some filler words to add bytes",
                r.nextLong(500, 100_000),
                r.nextInt(0, 200),
                Instant.now());
    }
}
