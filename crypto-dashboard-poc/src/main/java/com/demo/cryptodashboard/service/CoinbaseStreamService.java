package com.demo.cryptodashboard.service;

import com.demo.cryptodashboard.model.PriceTick;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Streams ticker updates from Coinbase Exchange's public WebSocket feed.
 *
 * Coinbase requires an explicit subscribe message after handshake, so this class
 * shows the {@code session.send().and(session.receive())} pattern - both directions
 * driven from the same reactive pipeline, no callbacks, no threads.
 */
@Slf4j
@Service
public class CoinbaseStreamService implements ExchangeStream {

    private static final String SOURCE = "coinbase";
    private static final URI WS_URI = URI.create("wss://ws-feed.exchange.coinbase.com");

    /** Coinbase uses BTC-USD style ids; we normalize to BTCUSDT to align with Binance. */
    private static final Map<String, String> PRODUCT_TO_SYMBOL = Map.of(
            "BTC-USD",  "BTCUSDT",
            "ETH-USD",  "ETHUSDT",
            "SOL-USD",  "SOLUSDT",
            "XRP-USD",  "XRPUSDT",
            "ADA-USD",  "ADAUSDT",
            "DOGE-USD", "DOGEUSDT",
            "AVAX-USD", "AVAXUSDT"
    );

    private static final List<String> PRODUCT_IDS = List.copyOf(PRODUCT_TO_SYMBOL.keySet());

    private final ObjectMapper mapper = new ObjectMapper();
    private final Sinks.Many<PriceTick> sink =
            Sinks.many().multicast().onBackpressureBuffer(1024, false);

    @Override public Flux<PriceTick> stream()  { return sink.asFlux(); }
    @Override public String source()           { return SOURCE; }

    @PostConstruct
    public void connect() {
        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();

        client.execute(WS_URI, session -> {
                    // 1. Build the subscribe frame.
                    String subscribeMsg = buildSubscribeMessage();

                    // 2. Send subscribe THEN consume ticks - both reactive, zero blocking.
                    Mono<Void> outbound = session.send(Mono.just(session.textMessage(subscribeMsg)));

                    Mono<Void> inbound = session.receive()
                            .map(WebSocketMessage::getPayloadAsText)
                            .doOnNext(this::handleMessage)
                            .doOnError(e -> log.warn("[coinbase] ws error: {}", e.toString()))
                            .then();

                    return outbound.then(inbound);
                })
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(rs -> log.info("[coinbase] reconnect attempt #{}", rs.totalRetries() + 1)))
                .subscribe(
                        null,
                        e -> log.error("[coinbase] terminal error", e),
                        () -> log.info("[coinbase] stream completed"));

        log.info("[coinbase] subscribed to {} products", PRODUCT_IDS.size());
    }

    private String buildSubscribeMessage() {
        // {"type":"subscribe","product_ids":["BTC-USD",...],"channels":["ticker"]}
        StringBuilder sb = new StringBuilder("{\"type\":\"subscribe\",\"product_ids\":[");
        for (int i = 0; i < PRODUCT_IDS.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(PRODUCT_IDS.get(i)).append('"');
        }
        sb.append("],\"channels\":[\"ticker\"]}");
        return sb.toString();
    }

    private void handleMessage(String payload) {
        try {
            JsonNode root = mapper.readTree(payload);
            if (!"ticker".equals(root.path("type").asText())) return;

            String productId = root.path("product_id").asText();
            String symbol = PRODUCT_TO_SYMBOL.get(productId);
            if (symbol == null) return;

            double price = root.path("price").asDouble();
            double open24h = root.path("open_24h").asDouble();
            double changePct = open24h > 0 ? ((price - open24h) / open24h) * 100.0 : 0.0;

            PriceTick tick = new PriceTick(
                    symbol,
                    price,
                    changePct,
                    root.path("volume_24h").asDouble(),
                    SOURCE,
                    Instant.now()
            );

            sink.tryEmitNext(tick);
        } catch (Exception e) {
            log.warn("[coinbase] parse error: {}", e.getMessage());
        }
    }
}
