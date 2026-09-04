package com.example.espoc.sync.repository;

import com.example.espoc.sync.model.NaiveProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NaiveProductRepository extends JpaRepository<NaiveProduct, String> {}
