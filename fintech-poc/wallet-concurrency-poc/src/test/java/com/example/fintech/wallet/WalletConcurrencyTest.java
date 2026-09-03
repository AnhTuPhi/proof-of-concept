package com.example.fintech.wallet;

import com.example.fintech.common.Currency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class WalletConcurrencyTest {

    @Autowired WalletService service;
    @Autowired WalletRepository wallets;
    @Autowired LedgerEntryRepository ledger;

    private String walletId;

    @BeforeEach
    void seed() {
        walletId = "wallet-" + UUID.randomUUID();
        wallets.save(new Wallet(walletId, new BigDecimal("100000"), Currency.VND));
    }

    @Test
    @DisplayName("Pessimistic: 100 concurrent debits of 1k from 100k → exactly 100 succeed, balance = 0")
    void pessimistic_correct() throws Exception {
        runConcurrentDebits("PESSIMISTIC", 100, new BigDecimal("1000"), 100);
    }

    @Test
    @DisplayName("Conditional UPDATE: 100 concurrent debits of 1k from 100k → exactly 100 succeed, balance = 0")
    void conditionalUpdate_correct() throws Exception {
        runConcurrentDebits("CONDITIONAL_UPDATE", 100, new BigDecimal("1000"), 100);
    }

    @Test
    @DisplayName("Optimistic: 100 concurrent debits → eventually exactly 100 succeed, no overdraw")
    void optimistic_correct() throws Exception {
        runConcurrentDebits("OPTIMISTIC", 100, new BigDecimal("1000"), 100);
    }

    @Test
    @DisplayName("Overdraw rejection: 150 attempts of 1k against 100k → exactly 100 succeed, 50 rejected, no negative balance")
    void overdrawRejection_conditionalUpdate() throws Exception {
        ConcurrentResult result = runConcurrentDebitsCounting("CONDITIONAL_UPDATE", 150, new BigDecimal("1000"));

        assertEquals(100, result.successes, "exactly 100 debits must succeed");
        assertEquals(50, result.rejections, "exactly 50 attempts must be rejected as insufficient");
        assertEquals(0, wallets.findById(walletId).orElseThrow().getBalance().signum(),
                "balance must land at zero, never negative");
    }

    private void runConcurrentDebits(String strategy, int attempts, BigDecimal amount, int expectedSuccesses) throws Exception {
        ConcurrentResult result = runConcurrentDebitsCounting(strategy, attempts, amount);
        assertEquals(expectedSuccesses, result.successes,
                strategy + " must have exactly " + expectedSuccesses + " successful debits");
        Wallet finalWallet = wallets.findById(walletId).orElseThrow();
        BigDecimal expectedBalance = new BigDecimal("100000").subtract(amount.multiply(new BigDecimal(expectedSuccesses)));
        assertEquals(0, finalWallet.getBalance().compareTo(expectedBalance),
                strategy + " final balance must be " + expectedBalance + " but was " + finalWallet.getBalance());
        assertEquals(expectedSuccesses, ledger.countByWalletId(walletId),
                strategy + " must have exactly " + expectedSuccesses + " ledger entries");
    }

    private ConcurrentResult runConcurrentDebitsCounting(String strategy, int attempts, BigDecimal amount) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(32, attempts));
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(attempts);

        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    service.debit(walletId, amount, strategy);
                    successes.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    rejections.incrementAndGet();
                } catch (Exception ignored) {
                    rejections.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "all tasks must finish within 30s");
        pool.shutdown();

        return new ConcurrentResult(successes.get(), rejections.get());
    }

    private record ConcurrentResult(int successes, int rejections) {}
}
