package com.example.fintech.wallet;

import com.example.fintech.wallet.strategies.DebitStrategy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WalletService {

    private final Map<String, DebitStrategy> strategies;
    private final WalletRepository wallets;

    public WalletService(List<DebitStrategy> strategyBeans, WalletRepository wallets) {
        this.strategies = strategyBeans.stream()
                .collect(Collectors.toMap(DebitStrategy::name, Function.identity()));
        this.wallets = wallets;
    }

    public BigDecimal debit(String walletId, BigDecimal amount, String strategyName) {
        DebitStrategy strategy = strategies.get(strategyName);
        if (strategy == null) {
            throw new IllegalArgumentException(
                    "Unknown strategy: " + strategyName + " (available: " + strategies.keySet() + ")");
        }
        return strategy.debit(walletId, amount);
    }

    public Wallet get(String id) {
        return wallets.findById(id).orElseThrow();
    }
}
