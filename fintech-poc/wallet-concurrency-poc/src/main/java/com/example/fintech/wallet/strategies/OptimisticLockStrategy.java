package com.example.fintech.wallet.strategies;

import com.example.fintech.wallet.*;
import jakarta.persistence.OptimisticLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Component
public class OptimisticLockStrategy implements DebitStrategy {

    private static final int MAX_RETRIES = 64;

    private final WalletRepository wallets;
    private final LedgerEntryRepository ledger;

    public OptimisticLockStrategy(WalletRepository wallets, LedgerEntryRepository ledger) {
        this.wallets = wallets;
        this.ledger = ledger;
    }

    @Override
    public String name() { return "OPTIMISTIC"; }

    @Override
    public BigDecimal debit(String walletId, BigDecimal amount) {
        long backoffNanos = 200_000L;
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return tryDebit(walletId, amount);
            } catch (OptimisticLockException | ObjectOptimisticLockingFailureException e) {
                long jitter = rng.nextLong(backoffNanos);
                sleep(backoffNanos + jitter);
                backoffNanos = Math.min(backoffNanos * 2, 20_000_000L);
            }
        }
        throw new IllegalStateException("Gave up after " + MAX_RETRIES + " optimistic retries");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BigDecimal tryDebit(String walletId, BigDecimal amount) {
        Wallet w = wallets.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("No wallet " + walletId));
        w.debit(amount);
        wallets.saveAndFlush(w);
        ledger.save(new LedgerEntry(walletId, LedgerEntry.EntryType.DEBIT, amount, w.getBalance(), name()));
        return w.getBalance();
    }

    private static void sleep(long nanos) {
        try { Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
