package com.example.espoc.sync.repository;

import com.example.espoc.sync.model.CdcProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CdcProductRepository extends JpaRepository<CdcProduct, String> {}
