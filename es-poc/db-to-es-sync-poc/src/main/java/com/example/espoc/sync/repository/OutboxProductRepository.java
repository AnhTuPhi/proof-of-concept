package com.example.espoc.sync.repository;

import com.example.espoc.sync.model.OutboxProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OutboxProductRepository extends JpaRepository<OutboxProduct, String> {}
