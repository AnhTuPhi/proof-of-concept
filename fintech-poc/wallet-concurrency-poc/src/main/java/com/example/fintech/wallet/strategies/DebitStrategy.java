package com.example.fintech.wallet.strategies;

import java.math.BigDecimal;

public interface DebitStrategy {
    String name();
    BigDecimal debit(String walletId, BigDecimal amount);
}
