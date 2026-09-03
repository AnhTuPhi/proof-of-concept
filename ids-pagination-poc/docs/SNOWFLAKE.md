# Cấu trúc của một Snowflake ID

Không chỉ có worker ID và sequence — một Snowflake ID là **64 bit** được chia thành **4 phần**, mỗi phần đóng một vai trò riêng:

```
┌─┬─────────────────────────────────────────┬──────────┬──────────┬────────────┐
│0│           timestamp (41 bit)            │ dc (5 b) │ w  (5 b) │ seq (12 b) │
└─┴─────────────────────────────────────────┴──────────┴──────────┴────────────┘
 ↑               ↑                              ↑          ↑           ↑
 sign           thời gian                   datacenter   worker     sequence
(1 bit)      (ms từ epoch)                    (0-31)     (0-31)    (0-4095)
```

Tổng cộng: `1 + 41 + 5 + 5 + 12 = 64 bit` → vừa khít một `long` (BIGINT trong DB).

---

## Giải thích từng phần

### 1. Sign bit (1 bit) — bit dấu

Luôn luôn bằng **0**. Lý do: trong Java/SQL, kiểu `long` / `BIGINT` là **số có dấu** (signed). Nếu bit này là 1, ID sẽ thành số âm, gây rắc rối cho việc sắp xếp và lưu trữ. Vì vậy ta "hy sinh" 1 bit này để giữ ID luôn dương.

### 2. Timestamp (41 bit) — thời gian

Đây là phần **quan trọng nhất**, quyết định ID có thể sắp xếp được theo thời gian. Nó lưu số **mili-giây kể từ một mốc thời gian tuỳ chọn** (gọi là *custom epoch*).

Trong code của bạn:

```java
private static final long EPOCH_MILLIS = 1704067200000L; // 2024-01-01T00:00:00Z
```

Tại sao không dùng Unix epoch (1970)? Vì 41 bit chỉ chứa được khoảng **69 năm** (`2^41 ms ≈ 69 năm`). Nếu bắt đầu từ 1970, đến năm 2039 là tràn. Nếu chọn epoch là 2024, hệ thống chạy được đến tận **2093**.

Đây cũng là lý do Snowflake được gọi là **time-sortable**: vì timestamp nằm ở các bit cao nhất, khi bạn so sánh hai ID dưới dạng số, ID nào lớn hơn → được tạo sau.

### 3. Datacenter ID (5 bit) + Worker ID (5 bit) — định danh máy

5 bit = 32 giá trị (0–31). Hai phần này gộp lại = 10 bit = **1024 máy** có thể chạy generator cùng lúc mà không trùng ID.

- **Datacenter ID**: máy đang chạy ở data center nào (DC1, DC2, …).
- **Worker ID**: trong data center đó, đây là máy/pod thứ mấy.

Quan trọng: **mỗi instance phải có một cặp `(dc, worker)` duy nhất** ngay từ lúc khởi động. Nếu hai pod cùng dùng `(1, 1)` thì sẽ sinh ra ID trùng nhau → mất dữ liệu.

Đây chính là lý do Snowflake **khó dùng với K8s Deployment** (xem `docs/SNOWFLAKE.md`): pod tên ngẫu nhiên, không biết tự gán worker ID kiểu gì.

### 4. Sequence (12 bit) — bộ đếm trong cùng mili-giây

12 bit = **4096 giá trị**. Đây là counter nội bộ của mỗi generator, tăng dần mỗi khi sinh ID trong **cùng một mili-giây**.

```
ms thứ N:   seq = 0, 1, 2, ..., 4095
ms thứ N+1: seq quay về 0
```

Ý nghĩa: trong một mili-giây, một (dc, worker) có thể sinh tối đa **4096 ID** mà vẫn đảm bảo không trùng. Tức là một máy có thể sinh tới **4,096,000 ID/giây** (≈ 4 triệu/giây).

Nếu trong 1 ms mà sequence chạy hết 4096 → generator phải **chờ sang ms tiếp theo** (`waitNextMillis` trong code).

---

## Ví dụ thực tế từ POC

Khi chạy `DemoRunner`, bạn thấy:

```
id=319182165613088768  ts=2026-05-30T18:35:58.400Z  dc=1  worker=1  seq=0
id=319182165696974848  ts=2026-05-30T18:35:58.420Z  dc=1  worker=1  seq=0
id=319182165696974849  ts=2026-05-30T18:35:58.420Z  dc=1  worker=1  seq=1
id=319182165696974850  ts=2026-05-30T18:35:58.420Z  dc=1  worker=1  seq=2
```

Đọc theo cấu trúc:

- `dc=1, worker=1`: cùng một máy sinh ra.
- ID đầu tiên ở ms `.400`, sequence reset về 0.
- Ba ID cuối cùng ở **cùng** ms `.420`, sequence tăng dần 0 → 1 → 2.
- Các ID này **strictly increasing** vì timestamp đứng ở bit cao nhất.

---

## Cách 4 phần được ghép lại (bit shift)

Code thực tế trong `SnowflakeIdGenerator.nextId()`:

```java
return ((now - EPOCH_MILLIS) << TIMESTAMP_SHIFT)  // dịch timestamp lên 22 bit trên cùng
    | (datacenterId << DATACENTER_SHIFT)          // dịch dc lên 17 bit
    | (workerId << WORKER_SHIFT)                  // dịch worker lên 12 bit
    | sequence;                                   // sequence ở 12 bit thấp nhất
```

Toán tử `<<` dịch trái, `|` ghép các phần lại. Kết quả là một số 64-bit chứa đủ cả 4 trường.

Để **giải mã** ngược lại:

```java
SnowflakeIdGenerator.timestampOf(id);     // dịch phải 22 bit, cộng EPOCH
SnowflakeIdGenerator.datacenterOf(id);    // dịch phải 17, mask 5 bit
SnowflakeIdGenerator.workerOf(id);        // dịch phải 12, mask 5 bit
SnowflakeIdGenerator.sequenceOf(id);      // mask 12 bit thấp
```

---

## Tóm lại — 4 thành phần, mỗi cái giải quyết một bài toán

| Phần          | Bit  | Vai trò                                             |
| ------------- | ---- | --------------------------------------------------- |
| **Sign**      | 1    | Đảm bảo ID luôn dương                               |
| **Timestamp** | 41   | Sắp xếp theo thời gian, debug "khi nào sinh ra"     |
| **Datacenter**| 5    | Phân biệt theo data center                          |
| **Worker**    | 5    | Phân biệt máy/pod trong cùng data center            |
| **Sequence**  | 12   | Phân biệt nhiều ID sinh trong cùng 1 ms trên 1 máy  |

Bốn cái này phối hợp với nhau để đảm bảo: **ID không trùng** (sequence + worker), **sắp xếp được** (timestamp ở bit cao), và **vừa 1 BIGINT** (tổng đúng 63 bit + 1 sign).

Đây là lý do thiết kế của Twitter — gọn, nhanh, không cần DB, nhưng đánh đổi: bạn **phải quản lý worker ID** ở tầng deployment. Nếu không muốn quản lý, dùng **ULID/UUIDv7** (không có worker bit, không cần phối hợp).

# Snowflake on Kubernetes — what actually fits

## Re-framing the question

It's not "K8s vs Snowflake." It's **"environments with stable per-process identity"**
vs **"environments without it."**

Snowflake's design (Twitter, 2010) assumes each generator instance has a permanent,
unique `(dc, worker)` tag — like a row in a server inventory. Anything that gives
you that, fits. Anything that doesn't, breaks.

K8s `Deployment` doesn't give it. K8s `StatefulSet` does. So **K8s itself is fine —
it just depends which workload type you pick.**

---

## Deployment scenarios ranked by Snowflake fit

### Best fit (stable identity, no coordination needed)

1. **K8s `StatefulSet`** — pod ordinals (`app-0`, `app-1`, …) are stable across
   restarts and rollouts. Parse the ordinal → worker ID. This is the canonical
   K8s pattern for Snowflake.
2. **Bare-metal / fixed VM fleet** — each box gets a worker ID at provisioning
   time (Ansible/Terraform writes it to `/etc/snowflake/worker_id`). Identity
   persists across reboots forever. This is literally how Twitter ran it.
3. **Single-process monoliths** — one generator, no coordination at all.
   Trivially correct.

### Workable fit (needs a coordination layer)

4. **K8s `Deployment` + HPA + lease service** — init container reserves a worker ID
   from Redis/etcd with a TTL lease, main container heartbeats to keep it. Works,
   but it's a real subsystem you have to operate.
5. **VM auto-scaling groups (ASG/MIG)** — same pattern: instance pulls a worker ID
   from a coordination store on boot.
6. **ECS/Fargate tasks** — same again. Tasks are ephemeral, so you need a lease.

### Bad fit (don't try)

7. **K8s `Deployment` without coordination** — random pod hashes, no stable
   identity. Will collide.
8. **Serverless (Lambda, Cloud Run, Cloud Functions)** — cold starts spin up
   thousands of containers per second, each with no identity. Snowflake
   collapses; you'd need a per-invocation lease, which defeats the "no DB round
   trip" benefit entirely.
9. **Spot/preemptible-heavy fleets with high churn** — lease TTLs constantly
   expiring; you'll burn through the 1024-worker space if reclaim logic is sloppy.

---

## The honest 2026 take

Snowflake was the right answer in **2010** because:

- UUID was 128 bits (storage was expensive).
- B-tree indexes on random UUIDs caused massive page-split overhead.
- Twitter ran on fixed metal — stable identity was free.

In **2026**, two of those three constraints have softened:

- Storage and RAM are cheap. 16-byte PKs are no longer a big deal for most workloads.
- Most teams run on K8s / serverless / autoscaling — stable identity is **not**
  free anymore; it's actively expensive to maintain.

So the modern default has shifted. If you're starting fresh on K8s and your write
rate isn't Twitter-scale, the pragmatic pick is:

### **UUIDv7** (RFC 9562, 2024) — or **ULID**

Both are: 128-bit, time-sortable, no coordination, no worker IDs, no leases, no
init containers, no StatefulSet requirement.

You give up:

- 8 bytes per row (16 instead of 8) — usually negligible.
- The ability to decode "which worker generated this" from the ID — usually a
  debugging luxury, not a need.

You gain: a deployment story that works on `Deployment` + HPA + spot + serverless
without any plumbing.

---

## Recommendation for a small fleet (2–4 pods on K8s)

Pick one based on what you value:

| If you want…                                          | Pick                                                      |
| ----------------------------------------------------- | --------------------------------------------------------- |
| Smallest IDs, willing to use `StatefulSet`            | **Snowflake** + StatefulSet, parse ordinal                |
| Smallest IDs, must stay on `Deployment`               | **Snowflake** + Redis lease (init container)              |
| Zero ops, sortable, standard                          | **UUIDv7** or **ULID** on plain `Deployment`              |
| Zero ops, public URLs, not sortable                   | **NanoID** for public IDs + auto-increment `BIGINT` PK    |

For 2–4 pods specifically, the recommendation is **ULID or UUIDv7 on `Deployment`**.
You don't have the write volume that makes Snowflake's 8-byte advantage matter,
and you skip an entire class of operational pain.

The POC's `UlidGenerator` already does this — same monotonic guarantees, same
time-sortability, no `(dc, worker)` to coordinate.

---

## Quick reference: why a plain `Deployment` breaks Snowflake

`Deployment` → `ReplicaSet` → pods named like `myapp-7f8d9b6c5d-xk2pq`,
`myapp-7f8d9b6c5d-mn4rs`, …

Random hash suffixes. There's no stable way for a pod to look at itself and decide
"I am worker #2." If you hash `HOSTNAME` to a worker ID:

- Hash collisions → duplicate worker IDs → ID collisions.
- Pod restarts get a new name → worker ID reshuffles.
- HPA scales 2 → 4 → 2: the recycled pod has no idea which worker IDs are "free."

The 10 bits in Snowflake exist precisely so up to 1024 generator instances can run
concurrently without coordinating per-ID — but **only if** each instance gets a
unique slot at startup. K8s `Deployment` does not provide that; you must add it.
