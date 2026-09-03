package com.example.webapipoc.etag;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ProductStore {

    private final ConcurrentHashMap<Long, Product> data = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1000);

    @PostConstruct
    void seed() {
        save(new Product(idSeq.getAndIncrement(), "VND-VNM", "Vinamilk shares",
            new BigDecimal("72500"), 5_000, 1, Instant.now()));
        save(new Product(idSeq.getAndIncrement(), "VND-HPG", "Hoa Phat Group",
            new BigDecimal("28100"), 12_000, 1, Instant.now()));
        save(new Product(idSeq.getAndIncrement(), "VND-FPT", "FPT Corp",
            new BigDecimal("129000"), 1_500, 1, Instant.now()));
    }

    public Product save(Product p) {
        data.put(p.id(), p);
        return p;
    }

    public Optional<Product> findById(Long id) {
        return Optional.ofNullable(data.get(id));
    }

    public List<Product> findAll() {
        return data.values().stream()
            .sorted((a, b) -> Long.compare(a.id(), b.id()))
            .toList();
    }

    public Optional<Product> update(Long id, String name, BigDecimal price, Integer stock) {
        return Optional.ofNullable(data.computeIfPresent(id, (k, cur) -> new Product(
            cur.id(),
            cur.code(),
            name != null ? name : cur.name(),
            price != null ? price : cur.price(),
            stock != null ? stock : cur.stock(),
            cur.version() + 1,
            Instant.now()
        )));
    }
}
