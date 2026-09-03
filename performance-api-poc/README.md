# perf-poc

POC 4 chủ đề performance Java back-end. Mỗi module chạy độc lập, Spring Boot 3.4 + Java 21.

| Module | Chủ đề | Port | Đọc |
|---|---|---|---|
| [perf-poc-bloom](perf-poc-bloom) | Bloom filter chặn cache penetration | 8081 | [README](perf-poc-bloom/README.md) |
| [perf-poc-consistent-hash](perf-poc-consistent-hash) | Consistent hash ring sharding Redis | 8082 | [README](perf-poc-consistent-hash/README.md) |
| [perf-poc-hikari](perf-poc-hikari) | Hikari pool tuning + p99 quan sát | 8083 | [README](perf-poc-hikari/README.md) |
| [perf-poc-n-plus-one](perf-poc-n-plus-one) | N+1 detection (Hibernate Statistics) + fetch strategies | 8084 | [README](perf-poc-n-plus-one/README.md) |

## Yêu cầu

- JDK 21
- Maven 3.9+
- Docker Desktop (cho Postgres + Redis nodes)

## Khởi động hạ tầng

```bash
docker compose up -d postgres redis-1 redis-2 redis-3
```

Postgres tự tạo 3 database: `poc_bloom`, `poc_hikari`, `poc_nplus1` (xem [docker/init.sql](docker/init.sql)).

Khi cần demo "thêm node" của consistent-hash:
```bash
docker compose --profile scale up -d redis-4
```

## Chạy 1 module

```bash
mvn -pl perf-poc-bloom -am spring-boot:run
mvn -pl perf-poc-consistent-hash -am spring-boot:run
mvn -pl perf-poc-hikari -am spring-boot:run
mvn -pl perf-poc-n-plus-one -am spring-boot:run
```

## Chạy 4 module song song

Mở 4 terminal, mỗi terminal chạy 1 lệnh trên. Hoặc dùng tmux/Windows Terminal split.

## Build cả 4

```bash
mvn -DskipTests clean package
```

## Chốt ý chính 4 chủ đề

| Chủ đề | Vấn đề giải quyết | Trade-off |
|---|---|---|
| **Bloom filter** | Cache miss tới DB cho key không tồn tại (penetration) | RAM cho bitset; false-positive 1% |
| **Consistent hashing** | `hash(key) % N` remap 100% khi thêm/bớt node | Cần TreeMap + virtual nodes; phân phối có thể lệch nếu vnode thấp |
| **Hikari tuning** | Pool quá nhỏ → queue, p99 vọt; quá lớn → DB context-switch | Cần load test thật, không đoán bừa |
| **N+1 detection** | Lazy collection trong loop → N round-trip DB | DTO + JOIN FETCH / EntityGraph; cartesian product nếu nhiều collection |

## Cấu trúc thư mục

```
perf-poc/
├── docker-compose.yml
├── docker/init.sql
├── pom.xml                        ← parent
├── perf-poc-bloom/
├── perf-poc-consistent-hash/
├── perf-poc-hikari/
└── perf-poc-n-plus-one/
```

## Đề xuất follow-up khi cần demo "đời thật"

- Cắm Prometheus + Grafana để xem hikari/n+1 metric thay vì curl /actuator/metrics
- Thêm JMeter / k6 script cho load test reproducible
- Thêm module **Redis Cluster** thay cho client-side consistent hashing (nếu team đã dùng Redis Cluster)
- Thêm module **Caffeine + Redis 2-tier cache** kèm singleflight để demo full cache-miss-storm prevention
