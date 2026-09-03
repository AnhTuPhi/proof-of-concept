package vn.com.poc.realtime.model;

import java.math.BigDecimal;
import java.time.Instant;

public record StockPrice(
        String symbol,
        BigDecimal price,
        BigDecimal change,
        BigDecimal changePercent,
        long volume,
        Instant timestamp
) {}
