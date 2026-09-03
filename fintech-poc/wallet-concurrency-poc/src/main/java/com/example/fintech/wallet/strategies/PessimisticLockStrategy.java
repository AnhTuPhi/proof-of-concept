package com.example.fintech.wallet.strategies;

import com.example.fintech.wallet.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Component
public class PessimisticLockStrategy implements DebitStrategy {

    private final WalletRepository wallets;
    private final LedgerEntryRepository ledger;

    public PessimisticLockStrategy(WalletRepository wallets, LedgerEntryRepository ledger) {
        this.wallets = wallets;
        this.ledger = ledger;
    }

    @Override
    public String name() { return "PESSIMISTIC"; }

    @Override
    @Transactional
    public BigDecimal debit(String walletId, BigDecimal amount) {
        Wallet w = wallets.findByIdForUpdate(walletId)
                .orElseThrow(() -> new IllegalArgumentException("No wallet " + walletId));
        w.debit(amount);
        ledger.save(new LedgerEntry(walletId, LedgerEntry.EntryType.DEBIT, amount, w.getBalance(), name()));
        return w.getBalance();
    }
}
