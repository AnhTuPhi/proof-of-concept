package com.demo.cryptodashboard.model;

import java.time.Instant;

/**
 * One tick of price data emitted by an upstream exchange.
 *
 * @param symbol        e.g. BTCUSDT
 * @param price         last traded price
 * @param changePercent 24h percentage change
 * @param volume        24h base-asset volume
 * @param source        which exchange the tick came from (binance/coinbase/...)
 * @param timestamp     server-side ingest time
 */
public record PriceTick(
        String symbol,
        double price,
        double changePercent,
        double volume,
        String source,
        Instant timestamp
) {}
