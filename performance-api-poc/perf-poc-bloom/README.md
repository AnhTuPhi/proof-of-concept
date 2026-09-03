# perf-poc-bloom

Demo dùng **Bloom filter** chặn cache-penetration: truy vấn key không tồn tại không cần đụng DB.

## Vấn đề thực tế

Khi cache miss (Redis trả `null`), service thường fallback xuống DB. Nếu attacker gửi liên tục
email không tồn tại, DB sẽ ngập query. Bloom filter ngồi trước cache: nếu nó nói
**"chắc chắn không tồn tại"** thì trả 404 ngay; nếu nó nói **"có thể có"** thì mới đi tiếp.

Trade-off: false-positive vẫn xảy ra (cấu hình `bloom.false-positive-probability=0.01` → 1%),
nhưng false-negative thì KHÔNG bao giờ. Đó là tính chất cốt lõi.

## Chạy

```bash
docker compose up -d postgres
mvn -pl perf-poc-bloom -am spring-boot:run
```

## Gọi thử

```bash
# 1) Seed 10K user → rebuild bloom filter
curl -X POST 'http://localhost:8081/api/seed?count=10000'

# 2) Bench: 5000 lượt, 95% truy vấn email không tồn tại
curl -X POST 'http://localhost:8081/api/bench?iterations=5000&missRatio=0.95'

# 3) Stats
curl http://localhost:8081/api/stats

# 4) Lookup riêng lẻ
curl http://localhost:8081/api/users/with-bloom/user42@dgo.local
curl http://localhost:8081/api/users/with-bloom/ghost@dgo.local
```

Kết quả `/api/bench` cho thấy `withBloom.dbHits` < `withoutBloom.dbHits` ≈ 95%
khi `missRatio=0.95` — đúng tỷ lệ bloom-reject.

## Lưu ý production

- **Rebuild định kỳ**: insert mới chỉ `put()` thêm; delete không "remove" được khỏi bloom standard
  (cần Counting Bloom hoặc rebuild offline).
- **Kích thước**: 200K entries × FPP 1% ≈ 240 KB RAM. Tăng FPP thì RAM giảm nhưng nhiều miss giả.
- **Phân tán**: bloom local mỗi instance đủ cho hot-key prevention. Nếu cần dùng chung,
  dùng RedisBloom module (`BF.ADD`, `BF.EXISTS`).
