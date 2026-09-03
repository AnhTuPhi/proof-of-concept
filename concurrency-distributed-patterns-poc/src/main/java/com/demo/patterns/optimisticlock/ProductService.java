package com.demo.patterns.optimisticlock;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository repo;

    public ProductService(ProductRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public Product create(String name, int stock) {
        return repo.save(new Product(name, stock));
    }

    /**
     * Decrement stock under JPA optimistic locking.
     *
     * If two transactions read the same Product (same version=v) and both try
     * to commit with version=v+1, the second flush throws OptimisticLockException
     * because the WHERE clause includes {@code version=v} and matches zero rows.
     */
    @Transactional
    public Product decrement(Long id, int amount) {
        Product p = repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Product " + id));
        p.decrement(amount);
        return p;
    }

    @Transactional(readOnly = true)
    public Product get(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Product " + id));
    }
}
