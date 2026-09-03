# perf-poc-n-plus-one

Demo **N+1 detection** với Hibernate Statistics + HandlerInterceptor đếm query/request,
kèm 4 fetch strategy để so sánh.

## Vấn đề thực tế

Lazy collection + loop access = N+1: 1 query lấy parents, N query lấy children.
Với 100 author × 5 book mỗi author → 101 query, 100 round-trip DB, p99 vọt lên ngay.

## Chạy

```bash
docker compose up -d postgres
mvn -pl perf-poc-n-plus-one -am spring-boot:run
```

## Gọi thử

```bash
# 1) Seed: 50 author × 5 book = 250 book
curl -X POST 'http://localhost:8084/api/library/seed?authors=50&booksPerAuthor=5'

# 2) Reset Hibernate Statistics
curl -X POST 'http://localhost:8084/api/library/stats/reset'

# 3) Gọi từng strategy — coi header X-Query-Count
curl -v http://localhost:8084/api/library/naive         # → N+1 (≈51)
curl -v http://localhost:8084/api/library/batch         # → ~2 query
curl -v http://localhost:8084/api/library/join-fetch    # → 1 query
curl -v http://localhost:8084/api/library/entity-graph  # → 1 query

# 4) Snapshot tổng số query
curl http://localhost:8084/api/library/stats
```

Bạn cũng sẽ thấy log WARN trên endpoint `/naive` (threshold mặc định = 5):
```
N+1 suspect — GET /api/library/naive fired 51 queries (threshold 5)
```

p6spy log SQL kèm thời gian execute từng câu.

## 4 strategy giải N+1

| Endpoint | Cách | Query count | Khi nào dùng |
|---|---|---|---|
| `/naive` | Lazy + loop | N+1 | KHÔNG bao giờ — đây là baseline để so |
| `/batch` | 1 query authors + 1 query `books WHERE author_id IN (...)` | 2 | Khi không thể JOIN (parent nhiều child + cartesian explode); chính là logic `@BatchSize` |
| `/join-fetch` | JPQL `LEFT JOIN FETCH` | 1 | Khi chỉ 1 collection cần fetch và size hợp lý |
| `/entity-graph` | `@EntityGraph(attributePaths=...)` | 1 | Tương tự join-fetch nhưng dùng lại được, không phải viết JPQL |

## Cảnh giác cartesian product

Nếu Author có 2 collection (books + awards) và fetch cả 2 bằng JOIN FETCH → kết quả nhân chéo,
hibernate có thể throw `MultipleBagFetchException` hoặc trả về duplicate row khổng lồ.
Giải pháp:
- Fetch 1 collection bằng JOIN FETCH, collection kia bằng `@BatchSize`
- Hoặc tách thành 2 query (2-step fetch pattern)

## QueryCountInterceptor — production note

Hibernate Statistics là **global** cho `SessionFactory`. Demo này hoạt động tốt với traffic
thấp / load test single-thread vì delta của 1 request không bị nhiễu.

Production-grade muốn count per-request chính xác:
- Dùng `datasource-proxy` hoặc p6spy với ThreadLocal counter
- Hoặc bật Hibernate session-level statistics: `session.getSessionFactory().openStatelessSession()` — out of scope POC
- Hoặc dùng Spring Boot's `ObservationRegistry` + Micrometer JDBC observation

## Trap: open-session-in-view

Spring Boot mặc định bật OSIV (`spring.jpa.open-in-view=true`). Lazy collection sẽ được
load trong View renderer — tức là controller trả về entity → Jackson serialize → đụng `books`
→ N+1 vẫn xảy ra DÙ service đã dùng JOIN FETCH (nếu DTO bị bypass).
**Khuyến nghị:** `spring.jpa.open-in-view=false` và luôn dùng DTO (như mã POC này).
