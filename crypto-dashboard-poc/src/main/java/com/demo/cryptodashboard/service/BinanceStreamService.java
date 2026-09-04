package com.demo.cryptodashboard.service;

import com.demo.cryptodashboard.model.PriceTick;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Streams 24-hour ticker updates from Binance's public WebSocket endpoint.
 *
 * Demonstrates several WebFlux strengths:
 *   1. Non-blocking WS client on Netty event loop (no thread per upstream symbol).
 *   2. A {@link Sinks.Many} multicast hub so N downstream SSE clients share one upstream
 *      connection - 1k browser tabs do NOT open 1k Binance sockets.
 *   3. Resilient reconnect with exponential backoff via {@link Retry#backoff}.
 */
@Slf4j
@Service
public class BinanceStreamService implements ExchangeStream {

    private static final String SOURCE = "binance";

    /** Public market data; no API key required. */
    private static final String WS_BASE = "wss://stream.binance.com:9443/stream?streams=";

    /** Symbols we want to track. Lowercase per Binance spec. */
    private static final List<String> SYMBOLS = List.of(
            "btcusdt", "ethusdt", "bnbusdt", "solusdt",
            "xrpusdt", "adausdt", "dogeusdt", "avaxusdt"
    );

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Multicast sink: one producer (WS callback) -> many consumers (SSE subscribers).
     * onBackpressureBuffer(1024, false) = drop oldest if a slow consumer falls behind,
     * so one stuck browser tab does NOT stall the others.
     */
    private final Sinks.Many<PriceTick> sink =
            Sinks.many().multicast().onBackpressureBuffer(1024, false);

    @Override
    public Flux<PriceTick> stream() {
        return sink.asFlux();
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @PostConstruct
    public void connect() {
        String streams = SYMBOLS.stream()
                .map(s -> s + "@ticker")
                .collect(Collectors.joining("/"));
        URI uri = URI.create(WS_BASE + streams);

        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();

        client.execute(uri, session -> session.receive()
                        .map(msg -> msg.getPayloadAsText())
                        .doOnNext(this::handleMessage)
                        .doOnError(e -> log.warn("[binance] ws error: {}", e.toString()))
                        .then())
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(rs -> log.info("[binance] reconnect attempt #{}", rs.totalRetries() + 1)))
                .subscribe(
                        null,
                        e -> log.error("[binance] terminal error after retries exhausted", e),
                        () -> log.info("[binance] stream completed"));

        log.info("[binance] subscribed to {} symbols", SYMBOLS.size());
    }

    private void handleMessage(String payload) {
        try {
            JsonNode root = mapper.readTree(payload);
            JsonNode data = root.path("data");
            if (data.isMissingNode()) return;

            PriceTick tick = new PriceTick(
                    data.path("s").asText(),
                    data.path("c").asDouble(),
                    data.path("P").asDouble(),
                    data.path("v").asDouble(),
                    SOURCE,
                    Instant.now()
            );

            Sinks.EmitResult result = sink.tryEmitNext(tick);
            if (result.isFailure()) {
                // FAIL_OVERFLOW etc -- not fatal, just record so we can see in metrics.
                log.debug("[binance] emit failure: {}", result);
            }
        } catch (Exception e) {
            log.warn("[binance] parse error: {}", e.getMessage());
        }
    }
}
