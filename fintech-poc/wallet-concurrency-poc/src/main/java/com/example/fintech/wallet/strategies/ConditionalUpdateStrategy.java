package com.example.fintech.wallet.strategies;

import com.example.fintech.wallet.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Component
public class ConditionalUpdateStrategy implements DebitStrategy {

    private final WalletRepository wallets;
    private final LedgerEntryRepository ledger;

    public ConditionalUpdateStrategy(WalletRepository wallets, LedgerEntryRepository ledger) {
        this.wallets = wallets;
        this.ledger = ledger;
    }

    @Override
    public String name() { return "CONDITIONAL_UPDATE"; }

    @Override
    @Transactional
    public BigDecimal debit(String walletId, BigDecimal amount) {
        int rows = wallets.conditionalDebit(walletId, amount);
        if (rows == 0) {
            throw new InsufficientFundsException(
                    "Conditional debit rejected for wallet " + walletId + " amount " + amount);
        }
        Wallet w = wallets.findById(walletId).orElseThrow();
        ledger.save(new LedgerEntry(walletId, LedgerEntry.EntryType.DEBIT, amount, w.getBalance(), name()));
        return w.getBalance();
    }
}
