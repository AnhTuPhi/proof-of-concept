package com.demo.cryptodashboard.service;

import com.demo.cryptodashboard.model.PriceTick;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single fan-in point that merges every {@link ExchangeStream} into one hot Flux.
 *
 * This is where the reactive composition really shines:
 *   - {@link Flux#merge(Iterable)} concatenates N upstreams onto a single pipeline
 *     in ~1 line of code.
 *   - {@link Flux#publish()}.refCount() turns it into a hot, share-once source so
 *     every controller subscriber reads from one merged stream, not N copies.
 *   - We keep a {@link ConcurrentHashMap} snapshot so a freshly-connected SSE client
 *     can be sent the last known price for every symbol immediately - no waiting
 *     for the next upstream tick.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceAggregatorService {

    private final List<ExchangeStream> exchanges;

    /** symbol+source -> latest tick. Lets new clients warm-start. */
    private final Map<String, PriceTick> latest = new ConcurrentHashMap<>();

    private Flux<PriceTick> hot;

    @PostConstruct
    public void init() {
        List<Flux<PriceTick>> sources = exchanges.stream()
                .map(ExchangeStream::stream)
                .toList();

        this.hot = Flux.merge(sources)
                .doOnNext(this::cacheLatest)
                // share() == publish().refCount(1) -- multicast to many subscribers,
                // but only stay subscribed upstream while >=1 subscriber exists.
                // Combined with the multicast Sinks below, this gives us:
                //   1 upstream WS connection per exchange  ->  N SSE clients.
                .share();

        log.info("aggregator wired {} exchanges: {}",
                exchanges.size(),
                exchanges.stream().map(ExchangeStream::source).toList());
    }

    /** Live merged stream from all exchanges. */
    public Flux<PriceTick> stream() {
        return hot;
    }

    /** Snapshot of last-known prices (so dashboards can render instantly on load). */
    public Flux<PriceTick> snapshot() {
        return Flux.fromIterable(latest.values());
    }

    /**
     * Demonstration helper: heartbeat tick so SSE connections stay warm
     * even when an exchange goes quiet (rare but happens).
     */
    public Flux<Long> heartbeats() {
        return Flux.interval(Duration.ofSeconds(15));
    }

    private void cacheLatest(PriceTick tick) {
        latest.put(tick.symbol() + "@" + tick.source(), tick);
    }
}
