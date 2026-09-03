# Realtime Patterns POC — Java 21 + Spring Boot 3.4

POC chứng minh 4 cách deliver real-time data từ server → client, **cùng share 1 nguồn data** (giả lập stock price ticker kiểu VNDirect).

## Stack

- Java **21** (virtual threads enabled)
- Spring Boot **3.4.3** — `spring-boot-starter-web` + `spring-boot-starter-websocket`
- gRPC **1.68** + protobuf **3.25** (HTTP/2 streaming)
- Vanilla HTML + SockJS + stomp.js cho demo UI

## Run

```bash
mvn spring-boot:run
```

Mở <http://localhost:8080/> — trang demo có 4 panel chạy song song, mỗi panel 1 transport.

> Lần build đầu cần internet để pull `protoc` binary đúng platform.

## 4 patterns

| Pattern | Endpoint | Khi nào nên dùng |
|---|---|---|
| **SSE** | `GET /sse/prices` (`text/event-stream`) | One-way server→client. Auto-reconnect built-in. Notification, ticker, progress bar. |
| **WebSocket + STOMP** | `WS /ws` (SockJS) → `SUBSCRIBE /topic/prices` | Full-duplex, low-latency, multi-topic. Chat, trading desk, multiplayer. |
| **Long-polling** | `GET /poll/prices?cursor=N&timeoutMs=25000` | Plan B khi WS bị firewall chặn. Cursor-based — không miss event. |
| **gRPC streaming** | gRPC `:9090` `PriceStreamService.streamPrices` (bi-stream) | Service-to-service, mobile. Protobuf type-safe, HTTP/2 multiplex. Browser cần grpc-web proxy. |

## Architecture

```
StockPriceGenerator (@Scheduled 500ms tick)
        │
        ▼
   PriceEventBus  ─── in-process pub/sub
        │
        ├──▶ SseController          ─▶ SseEmitter         ─▶ browser EventSource
        ├──▶ PriceBroadcaster       ─▶ STOMP /topic/...   ─▶ stomp.js client
        ├──▶ LongPollingController  ─▶ DeferredResult     ─▶ fetch() loop
        └──▶ PriceStreamingServiceImpl ─▶ gRPC StreamObserver ─▶ Java/grpcurl
```

Mỗi transport đăng ký 1 consumer trên `PriceEventBus`. Khi tick generator phát event, tất cả transports nhận đồng thời. Demo này tách concerns rõ: nguồn data 1 chỗ, transport nhiều chỗ.

## So sánh nhanh

| Tiêu chí | SSE | WebSocket | Long-polling | gRPC |
|---|---|---|---|---|
| Chiều | server→client | bi-directional | server→client (request-driven) | bi-directional |
| Protocol | HTTP/1.1 | TCP upgrade từ HTTP | HTTP/1.1 | HTTP/2 + protobuf |
| Browser native | ✅ EventSource | ✅ WebSocket API | ✅ fetch | ❌ cần grpc-web |
| Qua proxy/firewall | ✅ rất tốt | ⚠️ vài proxy chặn | ✅ rất tốt | ⚠️ HTTP/2 cần proxy hỗ trợ |
| Reconnect | tự động (`retry:` field) | client tự code | mỗi vòng tự reconnect | tự code hoặc keep-alive |
| Overhead/msg | thấp | thấp nhất | cao (HTTP headers mỗi request) | thấp nhất (binary) |
| Type-safe contract | ❌ | ❌ | ❌ | ✅ proto |

## Endpoints

- `GET  /` — Trang demo
- `GET  /sse/prices` — SSE stream
- `GET  /sse/stats` — Số client đang kết nối SSE
- `GET  /poll/prices?cursor=N&timeoutMs=25000` — Long-polling
- `WS   /ws` — STOMP endpoint (SockJS)
- Send `/app/ping` → nhận `/topic/pong` — STOMP ping/pong demo
- `GET  /api/grpc/demo?count=10&symbols=VND,FPT` — REST bridge gọi gRPC (vì browser không nói gRPC raw)
- gRPC `:9090` `vn.com.poc.realtime.grpc.PriceStreamService` — Native gRPC

## Test gRPC bằng grpcurl

```bash
# Server streaming
grpcurl -plaintext -d '{"symbols":["VND","FPT"]}' localhost:9090 \
  vn.com.poc.realtime.grpc.PriceStreamService/Watch
```

## Source tree

```
src/main/
├── java/vn/com/poc/realtime/
│   ├── RealtimePocApplication.java
│   ├── model/StockPrice.java
│   ├── service/{PriceEventBus, StockPriceGenerator}.java
│   ├── sse/SseController.java
│   ├── websocket/{WebSocketConfig, PriceBroadcaster}.java
│   ├── longpolling/LongPollingController.java
│   └── grpc/{GrpcServerConfig, PriceStreamingServiceImpl, GrpcDemoController}.java
├── proto/price_stream.proto
└── resources/
    ├── application.yml
    └── static/index.html
```

## Notes

- **gRPC port** mặc định `9090` — đổi bằng `--grpc.port=10000`.
- **HTTP port** mặc định `8080` — đổi bằng `--server.port=8081`.
- **Virtual threads** bật bằng `spring.threads.virtual.enabled=true` — long-polling scale tốt hơn vì mỗi waiter không chiếm platform thread.
- **Outbox / retry / persistence** không có trong POC này. Production cần thêm cho gRPC + WebSocket khi network glitch.
