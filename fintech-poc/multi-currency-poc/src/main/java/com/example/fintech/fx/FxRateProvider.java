package com.example.fintech.fx;

import com.example.fintech.common.Currency;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Component
public class FxRateProvider {

    private final Map<String, BigDecimal> rates = new HashMap<>();

    public FxRateProvider() {
        rates.put(key(Currency.USD, Currency.VND), new BigDecimal("24000.00"));
        rates.put(key(Currency.VND, Currency.USD), new BigDecimal("0.0000417"));
        rates.put(key(Currency.EUR, Currency.VND), new BigDecimal("26000.00"));
        rates.put(key(Currency.USD, Currency.EUR), new BigDecimal("0.92"));
    }

    public BigDecimal currentRate(Currency from, Currency to) {
        BigDecimal rate = rates.get(key(from, to));
        if (rate == null) {
            throw new IllegalArgumentException("No rate configured for " + from + "→" + to);
        }
        return rate;
    }

    public void setRate(Currency from, Currency to, BigDecimal rate) {
        rates.put(key(from, to), rate);
    }

    private static String key(Currency a, Currency b) { return a + "_" + b; }
}
