package com.demo.cryptodashboard.service;

import com.demo.cryptodashboard.model.PriceTick;
import reactor.core.publisher.Flux;

/**
 * Common contract for any upstream exchange feed.
 * Lets us merge Binance, Coinbase, (Kraken, ...) behind one polymorphic interface.
 */
public interface ExchangeStream {

    /** Hot flux of normalized price ticks. Safe for many subscribers. */
    Flux<PriceTick> stream();

    /** Human-readable source name, surfaced through PriceTick.source(). */
    String source();
}
