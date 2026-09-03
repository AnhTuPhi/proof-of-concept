# perf-poc-consistent-hash

Demo **consistent hashing** route key sang 3 Redis nodes, kèm so sánh với naive modulo router.

## Vấn đề thực tế

Sharding kiểu `hash(key) % N` rất phổ biến nhưng tệ khi thêm/bớt node: gần như TẤT CẢ key
phải remap → cache cold đồng loạt, DB ngập query (cache stampede).

Consistent hashing đặt cả node và key lên cùng vòng tròn 64-bit. Khi thêm 1 node,
chỉ ~K/(N+1) key (≈25% nếu N=3) cần đổi chỗ.

Virtual nodes (mỗi physical node nhân lên 150 điểm) giúp phân phối tải đều hơn —
nếu chỉ 1 điểm/node, một node "không may" có thể nhận 50% traffic.

## Chạy

```bash
docker compose up -d redis-1 redis-2 redis-3
mvn -pl perf-poc-consistent-hash -am spring-boot:run
```

## Gọi thử

### 1) Topology hiện tại
```bash
curl http://localhost:8082/api/topology
```

### 2) Seed 10K key, xem distribution
```bash
curl -X POST 'http://localhost:8082/api/keys/seed?count=10000'
curl http://localhost:8082/api/distribution
```
Mong đợi mỗi node ≈ 3300 ± 200. Nếu lệch nhiều → giảm `virtual-nodes-per-node`.

### 3) Mô phỏng thêm 1 node — so sánh remap rate
```bash
curl -X POST 'http://localhost:8082/api/simulate/add?sampleSize=10000'
```
Kết quả mẫu:
```json
{
  "consistentHash": { "remappedKeys": 2487, "remapRate": 0.2487 },
  "modulo":         { "remappedKeys": 7512, "remapRate": 0.7512 }
}
```
→ Consistent ≈ 25%, Modulo ≈ 75%. Đó là toàn bộ giá trị của thuật toán.

### 4) Thật sự thêm node (chạy thêm redis-4)
```bash
docker compose --profile scale up -d redis-4
curl -X POST 'http://localhost:8082/api/topology/node?name=redis-4&host=localhost&port=6382'
curl http://localhost:8082/api/distribution
```
Sau bước này, chỉ ~25% key của redis-1/2/3 cần được migrate sang redis-4 (logic migrate
không nằm trong scope POC này).

## Lưu ý production

- **Virtual nodes**: 100–200 là điểm bắt đầu hợp lý. Quá ít → lệch tải; quá nhiều → tốn RAM cho TreeMap.
- **Migration**: việc remap chỉ thay route map; key cũ vẫn nằm ở node cũ. Trong production
  cần background job migrate hoặc dùng "double read" (đọc cả node mới và cũ trong giai đoạn chuyển).
- **Redis Cluster** đã tích hợp sẵn 16384 hash slots — về bản chất là consistent hashing có sẵn.
  POC này hữu ích khi bạn muốn shard Memcached/custom KV hoặc shard nhiều resource khác (DB read replicas, queue partitions).
