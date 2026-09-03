# Performance Report — lmax-disruptor-poc

Tat ca so lieu duoi day duoc **do thuc te** tren chinh may chay POC nay bang
`BenchmarkRunner` (khong phai trich dan so lieu ly thuyet cua LMAX hay ben
thu ba). Moi bang so deu kem lenh de tu chay lai va kiem chung.

## Moi truong do

| | |
|---|---|
| CPU | Intel Core i7-10700K @ 3.80GHz — 8 core vat ly / 16 luong (hyper-threading) |
| JVM | OpenJDK Temurin 25.0.3 (bien dich `--release 21`, `maven.compiler.release=21`) |
| OS | Windows 11 Pro 64-bit |
| Disk | Samsung SSD 970 EVO Plus (NVMe) — anh huong truc tiep den do tre `fsync` cua `JournalHandler` |
| Disruptor | 4.0.0, `ProducerType.MULTI` |
| Outbox DB | H2 2.4.240 embedded (file mode), HikariCP 7.1.0, pool size 8 |
| JVM flags | Mac dinh (khong tune GC/heap rieng) — con nhieu du dia de toi uu them |

Moi lan chay dung cau hinh mac dinh cua `BenchmarkRunner` tru khi ghi chu
khac: `businessWorkers` = so luong core logic (8 hoac 16 tuy lan chay,
ghi ro trong tung bang), `outboxBatchSize=500`, `ringBufferSize=1,048,576`
(2^20).

---

## 1. Baseline throughput — 8 trieu event, happy path

```bash
java -jar target/lmax-disruptor-poc.jar mode=benchmark \
  sessions=8 eventsPerSession=1000000 ringBufferSize=1048576 \
  waitStrategy=yielding businessWorkers=8 outboxWorkers=2
```

| Giai doan | Ket qua |
|---|---|
| Tong event | 8,000,000 (8 session x 1,000,000, khong loi injected) |
| Producer wall time | 55.35s (8 thread producer hang lien tuc, khong nghi) |
| **Thoi gian toan chain drain** (checksum + journal fsync + business + outbox insert) | 60.48s |
| **Throughput ben ket** | **132,285 event/giay** |
| Latency p50 / p99 / p99.9 / max (tu luc publish den luc qua OutboxHandler) | 777.5ms / 7.32s / 7.34s / 7.34s |
| Outbox dispatch (2 thread, downstream 0% loi) | 8,000,000 dong trong **3m 12.5s** ≈ **41,600 dispatch/giay** |
| Reconciliation | `RESULT: OK` — moi dang thuc dung tuyet doi (xem [TEST_PLAN.md](TEST_PLAN.md)) |

**Doc so nay the nao:** 132K event/giay la thong luong **ben vung, qua toan
bo pipeline durable** (khong phai chi throughput cua rieng ring buffer —
LMAX cong bo ring buffer thuan co the dat vai chuc trieu event/giay khong co
I/O gi ca; con so 132K o day da bao gom **fsync moi batch** va **INSERT JDBC
batch vao H2** — hai buoc I/O thuc su). Day la con so **can dung de len ke
hoach cong suat**, khong phai con so "ly thuyet toi da cua Disruptor".

Dispatch throughput (41,600/giay) thap hon nhieu vi day la **UPDATE JDBC don
le** (khong batch duoc, vi moi dong co ket qua thanh cong/that bai rieng bi
quyet dinh SAU KHI goi downstream — khong the biet truoc de gop batch) tren
mot embedded H2 — mot RDBMS server that (Oracle, Postgres) voi connection
pool lon hon va WAL rieng se cho con so cao hon dang ke.

---

## 2. Anh huong cua Wait Strategy (1 trieu event, 4 phien)

```bash
java -jar target/lmax-disruptor-poc.jar mode=benchmark \
  sessions=4 eventsPerSession=250000 waitStrategy=<busy_spin|yielding|sleeping|blocking> \
  businessWorkers=8 outboxWorkers=2
```

| Wait strategy | Throughput (event/giay) | Chain drain time | p50 latency | Dispatch (1M dong, 2 thread) |
|---|---:|---:|---:|---:|
| `busy_spin` | **127,563** | 7.71s | 3.34s | 25.71s (~38,900/giay) |
| `yielding` | 111,553 | 8.89s | 2.45s | 26.93s (~37,100/giay) |
| `sleeping` | 112,687 | 8.72s | 2.15s | 29.35s (~34,100/giay) |
| `blocking` | 104,314 | 9.37s | 1.37s | 29.69s (~33,700/giay) |

**Throughput** xep hang dung nhu ly thuyet: `busy_spin` > `sleeping` ≈
`yielding` > `blocking` — cang it "nhuong" CPU cho he dieu hanh khi cho, cang
nhanh phan hoi khi co du lieu moi.

**Latency (p50) lai xep hang NGUOC LAI** — `busy_spin` co p50 CAO NHAT,
`blocking` co p50 THAP NHAT. Day khong phai loi do — day la mot **han che
phuong phap do** can noi ro:

> Ring buffer (`1,048,576` slot) **lon hon** tong so event (`1,000,000`), nen
> toan bo 1 trieu event duoc producer day vao trong **0.1-0.2 giay**, roi
> pipeline moi "duoi kip" trong 7.7-9.4 giay tiep theo. Do la mot **kich ban
> qua tai dot ngot (burst-then-drain)**, khong phai tai on dinh (steady-state).
> Trong kich ban nay, "latency" do duoc phan lon la **thoi gian cho trong
> hang doi (queueing delay)**, khong phai do tre xu ly thuan cua tung event —
> va thu tu drain cua tung wait strategy (busy-spin co the tranh CPU voi
> chinh cac thread producer/consumer khac tren may 16-luong nay) anh huong
> den PHAN BO cua queueing delay theo cach khong tuyen tinh, don gian.

**Ket luan dung du lieu nay chung minh duoc:** chon wait strategy anh huong
ro rang den **throughput ben vung** (bang so, nhat quan qua 4 lan chay).
**Chua chung minh duoc** (va khong nen suy dien tu bang nay): p50/p99 latency
"that" duoi tai on dinh — muon do dung con so do, can mot che do producer
**gioi han toc do** (rate-limited, thap hon throughput toi da cua pipeline) de
tranh hang doi bi don ung, hien **chua co** trong `BenchmarkRunner` (ghi vao
tech debt).

---

## 3. Chaos mode — loi feed + downstream loi 30% (200,000 event)

```bash
java -jar target/lmax-disruptor-poc.jar mode=chaos \
  sessions=4 eventsPerSession=50000 businessWorkers=8 outboxWorkers=3 \
  downstreamFailureRate=0.3
```

(dropRate/duplicateRate/corruptRate mac dinh cua `mode=chaos` la 0.05% moi
loai)

| Chi so | Gia tri |
|---|---|
| Attempted / produced | 200,000 / 199,982 (18 bi drop co chu dich) |
| Checksum that bai (corrupt) | 110 → quarantine, khong vao journal chinh |
| Gap phat hien (message mat) | 119 |
| Duplicate phat hien | 101 |
| Throughput ingest | 82,038 event/giay |
| Outbox tao ra | 199,771 |
| **Outbox dispatch thanh cong** | 199,303 |
| **Outbox retry (tong so lan thu lai)** | **85,353** |
| **Outbox dead-letter** (het 5 lan retry) | **468** (0.234% tren 199,771) |
| Thoi gian dispatch (3 thread, drain het) | 14.33s |
| Reconciliation cuoi cung | `RESULT: OK` — `outboxDispatched + outboxDeadLettered == outboxCreated` (199,303 + 468 = 199,771) |

**So sanh voi ly thuyet:** voi ty le loi downstream 30%/lan va toi da 5 lan
thu (`RetryBackoffPolicy.defaultPolicy()`), xac suat MOT dong het sach 5 lan
deu that bai la `0.3^5 = 0.243%`. So do luong thuc te: `468 / 199,771 =
0.234%` — **rat khop** voi du bao ly thuyet (chenh lech nam trong bien do
ngau nhien binh thuong). Day la mot phep kiem chung chat che rang co che
retry/backoff hoat dong **dung nhu thiet ke toan hoc cua no**, khong chi
"co ve hoat dong".

**85,353 lan retry** cho 199,771 dong (trung binh ~0.43 retry/dong) la con
so hop ly: voi p(fail)=0.3, so lan thu ky vong truoc khi thanh cong (hoac bi
loai) xap xi `1/(1-0.3) ≈ 1.43` lan → ~0.43 retry/dong tren trung binh —
khop voi so do duoc.

---

## 4. Hai bug hieu nang thuc su phat hien duoc trong qua trinh benchmark

Ca hai deu duoc sua **truoc khi** lay so lieu o muc 1-3, va deu duoc ghi lai
chi tiet trong [TECHNICAL.md](../TECHNICAL.md) — nhung dang nhac lai o day vi
day chinh la gia tri thuc te cua viec **benchmark that**, khong chi doc code:

1. **Fsync-per-tiny-batch.** Ban dau, benchmark 8 trieu event khong bao gio
   hoan thanh trong 300 giay dau. Nguyen nhan: khi consumer "duoi kip" producer
   qua nhanh, moi batch cua Disruptor chi co 1-2 event, khien `JournalHandler`
   goi `fsync` gan nhu tren TUNG event — hang trieu fsync rieng le thay vi
   vai nghin batch. Da fix bang cach dam bao thiet ke group-commit dung y
   nghia (xem TECHNICAL.md muc 3).
2. **`claimBatch()` co `ORDER BY id`** — gay do phuc tap gan O(n^2): cang ve
   sau qua trinh dispatch, cang phai bo qua nhieu dong DA claim (nam truoc
   trong thu tu id) truoc khi tim duoc dong PENDING tiep theo. O quy mo 8
   trieu dong, dispatch khong bao gio ket thuc trong thoi gian cho phep. Fix:
   bo `ORDER BY id` (khong can thiet ve mat dung dan), de H2 dung thang index
   `(status, next_attempt_at)`.

Bai hoc chung: **ca hai bug deu la loai loi ma review code tinh (static) rat
kho phat hien** — ca hai chi lo dien khi chay that voi khoi luong du lon.
Day chinh la ly do "benchmark that, khong doan" duoc dat len hang dau trong
yeu cau cua POC nay.

---

## 5. Gioi han cua bao cao nay (doc truoc khi trich dan so lieu)

- Tat ca so lieu chay tren **mot may desktop**, khong phai server production
  (khong co NUMA, khong co RAID, mang that). So tuyet doi se khac tren ha
  tang that — nhung **ty le tuong doi** giua cac wait strategy, va **cong
  thuc dead-letter khop ly thuyet**, la nhung ket luan von dung o moi quy mo.
- Outbox dispatch bi gioi han boi **H2 embedded**, khong phai boi thiet ke
  outbox pattern — mot RDBMS server that se cho throughput dispatch cao hon
  dang ke (xem TECHNICAL.md muc 5).
- Latency percentile o muc 2 do trong kich ban **burst-then-drain**, khong
  phai steady-state — xem canh bao chi tiet o do.
- Chua co benchmark voi **nhieu instance/JVM that** (chi co concurrency
  trong 1 JVM) — xem [CONSISTENCY.md](../CONSISTENCY.md) cho ke hoach mo
  rong va nhung gi se thay doi ve mat hieu nang khi do.
