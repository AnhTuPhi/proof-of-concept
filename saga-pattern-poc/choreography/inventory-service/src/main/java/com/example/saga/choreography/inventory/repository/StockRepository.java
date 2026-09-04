package com.example.saga.choreography.inventory.repository;

import com.example.saga.choreography.inventory.domain.Stock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, String> {

    /**
     * Pessimistic write lock — taken when committing or releasing inventory so
     * concurrent sagas for the same SKU serialize correctly.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.productId = :productId")
    Optional<Stock> findForUpdate(@Param("productId") String productId);
}
