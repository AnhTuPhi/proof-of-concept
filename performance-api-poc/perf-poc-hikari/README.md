# perf-poc-hikari

Demo **HikariCP** tuning: pool size, connection timeout, leak detection, p50/p95/p99
quan sát dưới tải.

## Vấn đề thực tế

Pool quá nhỏ → request queue, p99 nhảy vọt khi vượt `maximum-pool-size`.
Pool quá lớn → DB context-switch, lock contention, thực tế *chậm hơn*.

Quy tắc Hikari (xem [HikariCP wiki](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)):

```
connections = ((core_count * 2) + effective_spindle_count)
```

Với DB SSD và app 8 core → ~20 connection. Đừng tự tăng "to ăn chắc" lên 200.

## Chạy

```bash
docker compose up -d postgres
mvn -pl perf-poc-hikari -am spring-boot:run
```

Mặc định pool=10. Đổi profile:
```bash
mvn -pl perf-poc-hikari -am spring-boot:run -Dspring-boot.run.profiles=small-pool
mvn -pl perf-poc-hikari -am spring-boot:run -Dspring-boot.run.profiles=tuned
```

## Gọi thử

### 1) Single work — giữ connection 100ms
```bash
curl 'http://localhost:8083/api/work?ms=100'
```

### 2) Pool stats hiện tại
```bash
curl http://localhost:8083/api/pool
```

### 3) Load test — đo p50/p95/p99
```bash
# 50 concurrent, 500 total, mỗi request giữ connection 100ms
curl -X POST 'http://localhost:8083/api/load?concurrency=50&total=500&workMs=100'
```

Với pool=10 và concurrency=50 → bạn sẽ thấy:
- `threadsAwaitingConnection` > 0 (xem /api/pool song song)
- p99 ≈ 5× p50 do queue

Chuyển sang profile `tuned` (pool=20) → p99 giảm đáng kể.

### 4) Prometheus metrics
```bash
curl http://localhost:8083/actuator/prometheus | grep hikari
```
Các metric quan trọng:
- `hikaricp_connections_active`
- `hikaricp_connections_pending`
- `hikaricp_connections_acquire_seconds` (histogram → cấp vào Grafana → p99)

## Tham số đáng tune

| Tham số | Ý nghĩa | Khi cần đụng |
|---|---|---|
| `maximum-pool-size` | Số connection tối đa | Đo bằng load test, đừng đoán |
| `minimum-idle` | Giữ luôn sẵn N idle | Set bằng max-pool nếu burst đột ngột (tránh chờ tạo connection) |
| `connection-timeout` | Chờ tối đa khi pool đầy | Quá ngắn → fail nhanh; quá dài → request "ngồi" gây timeout downstream |
| `leak-detection-threshold` | Báo nếu giữ connection > X ms | Bật khi nghi rò connection (forgot `close()`) |
| `idle-timeout` / `max-lifetime` | Recycle idle/old connection | Set < timeout của LB/firewall |

## Lưu ý

- `pg_sleep(?)` thực sự chiếm connection ở DB side — đúng tinh thần benchmark pool, không
  phải sleep ở app side (sẽ không chiếm connection và che mất queue behavior).
- Đừng dùng `/api/load` với `total` quá lớn nếu pool nhỏ — chờ rất lâu vì queue.
