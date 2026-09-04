package com.demo.cryptodashboard.controller;

import com.demo.cryptodashboard.model.PriceTick;
import com.demo.cryptodashboard.service.PriceAggregatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Streams aggregated price ticks to browsers via Server-Sent Events.
 *
 * Showcases:
 *   - Reactive filter() for per-client symbol selection.
 *   - groupBy() + sample() to throttle each symbol independently (key for backpressure
 *     demo: upstream firing 100 ticks/sec for BTC, client wants at most 5/sec).
 *   - mergeWith() to interleave a keep-alive heartbeat onto the data stream.
 *   - A simple subscriber counter so we can sanity-check during load test
 *     ("yes, 5000 SSE clients are connected to one JVM").
 */
@Slf4j
@RestController
@RequestMapping("/api/prices")
@RequiredArgsConstructor
public class PriceController {

    private final PriceAggregatorService aggregator;
    private final AtomicLong activeSubscribers = new AtomicLong();

    /**
     * Live SSE feed.
     *
     * Query params:
     *   symbols  - comma list, e.g. ?symbols=BTCUSDT,ETHUSDT (case-insensitive).
     *              Omit to receive ALL symbols.
     *   maxHz    - per-symbol max emit rate (Hz). Default 10. Set lower to demo
     *              backpressure throttling under heavy upstream load.
     *   sources  - filter by exchange, e.g. ?sources=binance,coinbase
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<PriceTick>> stream(
            @RequestParam(required = false) Set<String> symbols,
            @RequestParam(required = false) Set<String> sources,
            @RequestParam(defaultValue = "10") int maxHz
    ) {
        Set<String> symbolFilter = upper(symbols);
        Set<String> sourceFilter = lower(sources);
        Duration window = Duration.ofMillis(Math.max(1, 1000 / Math.max(1, maxHz)));

        // 1. Send the last-known tick for each symbol immediately so the UI is not blank.
        Flux<PriceTick> warmup = aggregator.snapshot()
                .filter(t -> matches(t, symbolFilter, sourceFilter));

        // 2. Live stream, filtered, then throttled per symbol via groupBy/sample.
        Flux<PriceTick> live = aggregator.stream()
                .filter(t -> matches(t, symbolFilter, sourceFilter))
                .groupBy(PriceTick::symbol)
                .flatMap(group -> group.sample(window));

        // 3. Concatenate warm-up snapshot in front of the live feed.
        Flux<ServerSentEvent<PriceTick>> data = Flux.concat(warmup, live)
                .map(tick -> ServerSentEvent.<PriceTick>builder()
                        .event("price")
                        .data(tick)
                        .build());

        // 4. Heartbeat - keeps proxies/load balancers from closing the connection.
        Flux<ServerSentEvent<PriceTick>> heartbeat = aggregator.heartbeats()
                .map(i -> ServerSentEvent.<PriceTick>builder()
                        .event("ping")
                        .comment("keep-alive #" + i)
                        .build());

        return data.mergeWith(heartbeat)
                .doOnSubscribe(s -> {
                    long n = activeSubscribers.incrementAndGet();
                    log.debug("SSE +1 (total={})", n);
                })
                .doFinally(sig -> {
                    long n = activeSubscribers.decrementAndGet();
                    log.debug("SSE -1 (total={}, reason={})", n, sig);
                });
    }

    /** Simple counter endpoint used during load test sanity checks. */
    @GetMapping("/subscribers")
    public long subscribers() {
        return activeSubscribers.get();
    }

    /* --------------------------------------------------------- */

    private static boolean matches(PriceTick t, Set<String> symbols, Set<String> sources) {
        if (!symbols.isEmpty() && !symbols.contains(t.symbol())) return false;
        if (!sources.isEmpty() && !sources.contains(t.source())) return false;
        return true;
    }

    private static Set<String> upper(Set<String> in) {
        return in == null ? Set.of() : in.stream().map(String::toUpperCase).collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> lower(Set<String> in) {
        return in == null ? Set.of() : in.stream().map(String::toLowerCase).collect(Collectors.toUnmodifiableSet());
    }
}
