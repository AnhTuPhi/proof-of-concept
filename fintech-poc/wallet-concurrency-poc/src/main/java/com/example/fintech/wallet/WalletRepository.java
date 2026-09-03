package com.example.fintech.wallet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") String id);

    @Modifying
    @Query("update Wallet w set w.balance = w.balance - :amount, w.version = w.version + 1 " +
            "where w.id = :id and w.balance >= :amount")
    int conditionalDebit(@Param("id") String id, @Param("amount") BigDecimal amount);
}
