# Test Plan — lmax-disruptor-poc

Muc tieu cua ke hoach kiem thu nay: chung minh 5 yeu cau cua bai toan goc
([ISSUE.md](../ISSUE.md)) — **thread-safe, toan ven, nhan du, xu ly du,
retry/backoff/outbox co gioi han** — bang so lieu kiem chung duoc, khong
phai bang suy luan. Moi tang kiem thu duoi day nham vao dung 1 hoac vai yeu
cau cu the, va noi ro test nao (da tu dong hoa) chung minh dieu gi.

## Tom tat nhanh

| Tang | Muc tieu | So test | Trang thai |
|---|---|---|---|
| 1. Unit | Tung don vi logic dung lap tai, tach biet | 58 | ✅ tu dong (`mvn test`) |
| 2. Correctness / Reconciliation | Toan bo pipeline nhan du + xu ly du, dung so | 1 | ✅ tu dong |
| 3. Concurrency stress | Thread-safe duoi tai da luong, lap lai nhieu lan | 10 | ✅ tu dong |
| 4. Outbox chaos (retry/backoff/dead-letter) | Downstream loi ngau nhien / loi lien tuc deu duoc xu ly dung | 4 | ✅ tu dong |
| 5. Performance / Benchmark | Throughput, latency percentile, tac dong cua wait strategy | — | ✅ thu cong qua `BenchmarkRunner`, so lieu o [PERFORMANCE.md](PERFORMANCE.md) |
| 6. Crash-recovery (journal replay) | JVM chet giua chung, journal con lai co dung khong | — | ⚠️ **chua tu dong hoa** — xem muc 6 |
| 7. Multi-instance / horizontal | Nhieu instance dung chung outbox, khong dispatch trung | 1 (rut gon, gia lap trong 1 JVM) | ⚠️ mo phong 1 phan, xem muc 7 |

Tong: **73 test tu dong**, chay bang `./mvnw -q test`, khong can infra ngoai
(H2 embedded, khong can Docker/network — tru lan dau tai dependency Maven).

---

## 1. Unit test

### 1.1 `IntegrityCheckHandlerTest` (5 test)
Kiem tra tung nhanh logic cua giai doan dau tien, **tach roi khoi Disruptor**
(goi thang `onEvent()`, khong can dung ring buffer that):

- Event hop le, dung thu tu -> khong co canh bao nao.
- Checksum sai (bit flip) -> `poisoned=true`, dem vao `integrityFailed`.
- Seq nhay vot -> `gapDetected` dung bang so luong bi mat, dem vao `gapsDetected`.
- Seq lui lai -> `duplicate=true`, dem vao `duplicatesDetected`, **khong** dem
  vao `gapsDetected` (hai loai loi khong duoc lan vao nhau).
- Nhieu session doc lap -> khong anh huong lan nhau (tranh loi "session A anh
  huong den seq-tracking cua session B" mot khi dung chung 1 mang
  `expectedNextSeq`).

**Chung minh yeu cau nao:** Toan ven (checksum), Nhan du (gap/duplicate).

### 1.2 `RetryBackoffPolicyTest` (53 test)
- `isExhaustedRespectsMaxAttempts` — bien gioi han retry dung.
- `nextDelayIsWithinFullJitterBounds` — chay **50 lan lap** (`@RepeatedTest`)
  vi cong thuc co ngau nhien (jitter); moi lan kiem tra delay nam trong
  `[0, min(cap, base*2^attempt)]` cho 8 muc attempt khac nhau — tong cong
  400 phep kiem tra bien, khong chi 1 lan may man.
- `delayNeverExceedsCapEvenAtHighAttemptCounts` — dam bao `base * 2^attempt`
  khong overflow hoac vuot cap khi attempt lon (chan edge-case so hoc).
- `defaultPolicyShape` — gia tri mac dinh dung nhu tai lieu.

**Chung minh yeu cau nao:** Retry/backoff dung cong thuc, khong retry-storm.

---

## 2. Correctness / Reconciliation — `PipelineReconciliationTest` (1 test, nhieu assertion)

Day la test **quan trong nhat** trong bo test — no chay toan bo pipeline
that (Disruptor + journal file that + H2 that) voi **ca 3 loai loi feed
cung luc** (drop 1%, duplicate 1%, corrupt 1%) tren 30,000 event, roi kiem
tra **tung dang thuc** trong `ReconciliationReport`:

```
received            == produced
integrityPassed + integrityFailed == received
journaled           == received - integrityFailed
businessProcessed    == journaled - duplicatesDetected
outboxCreated        == businessProcessed
outboxDispatched + outboxDeadLettered == outboxCreated   (sau khi dispatcher drain)
```

Neu bat ky dang thuc nao sai **du chi 1 don vi**, test fail va in ra toan bo
bang so — day chinh la co che da bat duoc mot bug thuc su trong qua trinh
xay POC nay (mot event vua bi corrupt vua la duplicate bi dem sai 2 lan, lech
di dung 5 don vi tren 120,000 event — xem lich su commit).

Test cung co "sanity check nguoc": khang dinh `integrityFailed > 0`,
`gapsDetected > 0`, `duplicatesDetected > 0` — de dam bao chinh test nay
khong pass mot cach vo nghia (vacuously) neu vi ly do gi do cac loai loi
khong duoc bat len.

**Chung minh yeu cau nao:** Toan ven, Nhan du, Xu ly du — dong thoi, bang so
chinh xac tuyet doi (khong phai "gan dung" hay "hau het").

**Gioi han da biet:** quy mo test nay duoc gioi han o 30,000 event / dispatch
qua H2 embedded do mot han che cua H2 MVStore duoi tai retry lon (xem
TECHNICAL.md muc 5, "Tech debt") — khong lien quan den logic pipeline. So
lieu throughput thuc te o quy mo trieu event nam o
[PERFORMANCE.md](PERFORMANCE.md), do bang `BenchmarkRunner` voi downstream
khong loi (tach rieng bien "throughput" khoi bien "H2 duoi retry-storm").

---

## 3. Concurrency stress — `ConcurrencyStressTest` (10 test, `@RepeatedTest`)

**Khong** inject loi gi ca — muc dich la chi hang lieu ring buffer voi nhieu
producer + nhieu business worker chay that su song song, **lap lai 10 lan
doc lap** (ring buffer moi moi lan), va kiem tra:

- `produced == totalAttempted` (khong drop event nao khi khong co drop-rate).
- Toan bo `ReconciliationReport` sach (giong muc 2, nhung happy-path).
- **Kiem tra chua tung co trong reconciliation report**: tong
  `tradeCount` cong don tu MOI `SymbolState` (qua tat ca cac shard) phai
  bang dung `produced` — day la phep kiem tra truc tiep cho invariant
  "sharding theo hash(symbol) khong bao gio double-count hay drop mot ma
  nao" (TECHNICAL.md muc 4).

10 lan lap, moi lan mot ring buffer/H2 hoan toan moi, chinh la ly do de tin
tuong day khong phai may man qua 1 lan chay — mot race condition trong
sharding hoac trong barrier cua Disruptor thuong chi lo dien qua nhieu lan
chay voi thoi diem interleaving khac nhau.

**Chung minh yeu cau nao:** Thread-safe (khong mat/trung du lieu duoi tai da
luong, lap lai nhieu lan doc lap de giam xac suat bo sot loi hiem).

---

## 4. Outbox chaos — `OutboxDispatcherTest` (4 test)

- `succeedsOnFirstAttemptWhenDownstreamIsHealthy` — duong hanh phuc.
- `retriesThenSucceedsAfterTransientFailures` — downstream loi dung 2 lan roi
  thanh cong o lan thu 3; kiem tra so lan goi va `outboxRetries >= 2`.
- `movesToDeadLetterAfterExhaustingRetries` — downstream loi vinh vien; kiem
  tra dong outbox chuyen sang `DEAD_LETTER` dung sau dung so lan
  `maxAttempts`, khong retry mai mai.
- `concurrentDispatchersNeverDoubleDispatchTheSameRow` — **4 dispatcher
  thread cung claim 200 dong**; kiem tra tong so lan goi `publish()` dung
  bang 200 (khong thua, khong thieu) — chung minh row-lock (`FOR UPDATE`)
  hoat dong dung khi co tranh chap thuc su.

**Chung minh yeu cau nao:** Retry/backoff/outbox/dead-letter — bao gom ca
truong hop nhieu dispatcher tranh chap (buoc dem cho kha nang chay nhieu
instance, xem CONSISTENCY.md).

---

## 5. Performance / Benchmark

Khong phai unit test pass/fail — day la do luong co so, chay thu cong qua
`BenchmarkRunner` (xem README.md phan "Chay thu"). Ke hoach do:

1. **Baseline throughput** — happy path (khong loi), quy mo trieu event, so
   sanh giua cac wait strategy (`busy_spin`, `yielding`, `sleeping`,
   `blocking`) de thay trade-off CPU/latency/throughput ro rang bang so.
2. **Latency percentile** — p50/p99/p99.9/max do bang HdrHistogram, tu luc
   producer stamp `ingestNanos` den luc `OutboxHandler` xu ly xong — phan
   anh **do tre toan pipeline**, khong chi rieng ring buffer.
3. **Group-commit sensitivity** — anh huong cua cadence fsync (endOfBatch)
   len throughput khi ty le producer/consumer thay doi — day la mot bai hoc
   thuc te phat hien duoc trong qua trinh benchmark (xem TECHNICAL.md muc 3).
4. **Chaos throughput** — throughput cua outbox dispatcher khi downstream co
   ty le loi cao (vd 30%), de biet retry-churn anh huong bao nhieu den
   dispatch throughput thuc te.

So lieu day du: [PERFORMANCE.md](PERFORMANCE.md).

---

## 6. Crash-recovery (journal replay) — CHUA tu dong hoa

**Vi sao chua co:** `JournalHandler` hien tai chi **ghi** WAL, chua co code
**doc lai** journal de replay sau crash (ghi ro trong TECHNICAL.md muc 3,
"Tech debt"). Day la buoc tiep theo ro rang neu POC nay duoc phat trien
them, khong phai thieu sot bi bo qua.

**Ke hoach thu cong de xac minh (chua tu dong hoa duoc voi kien truc hien
tai):**
1. Chay `BenchmarkRunner` voi mot luong event lon, `kill -9` tien trinh JVM
   giua chung (mo phong crash cung).
2. Doc file `journal.log` con lai bang tay (dinh dang pipe-delimited, de
   doc) — dem so dong, so voi `outboxCreated` da ghi duoc truoc luc crash
   (H2 file van con nguyen vi moi UPDATE da duoc commit).
3. Ky vong: so dong journal >= so dong outbox da COMMIT (vi journal duoc ghi
   TRUOC business logic va outbox — xem thu tu chain trong TECHNICAL.md muc 0),
   chung minh "khong co event nao duoc outbox ma khong duoc journal truoc do".
4. Viet mot bo doc journal (`JournalReplayTool`, chua ton tai) de tu dong hoa
   buoc nay — de xuat cho lan lap tiep theo cua POC.

## 7. Multi-instance / horizontal — mo phong mot phan

`OutboxDispatcherTest.concurrentDispatchersNeverDoubleDispatchTheSameRow`
mo phong **mot phan** cua kich ban nhieu instance: nhieu dispatcher thread
trong **cung 1 JVM** tranh nhau claim tu **cung 1 outbox table** — day chinh
la dieu se xay ra neu N instance (N process/pod khac nhau) deu tro toi cung
1 outbox database that (Oracle/Postgres). Test nay chung minh co che
`FOR UPDATE` an toan, nhung **khong** mo phong duoc:

- Nhieu JVM that (chi phi mang giua cac instance, GC doc lap, clock skew).
- Kich ban session failover giua cac instance (xem CONSISTENCY.md muc 1,
  "van de (2)") — day la kich ban nguy hiem nhat va **kho nhat de tu dong
  hoa thanh test** vi no doi hoi mo phong ca ha tang dieu phoi (load
  balancer / service discovery) ma POC nay khong co.

**De xuat cho production:** viet integration test dung Testcontainers +
Postgres that, chay N JVM process that (khong chi N thread) cung tro vao 1
container Postgres, kiem tra invariant tuong tu nhu muc 4 nhung o quy mo
process that.

---

## Cach chay toan bo ke hoach da tu dong hoa

```bash
./mvnw -q test                                    # tang 1-4 (73 test)
java -jar target/lmax-disruptor-poc.jar mode=benchmark ...   # tang 5, xem README.md
```

Khong can Docker, khong can cau hinh gi them — H2 embedded va journal file
deu nam trong `target/` cua tung lan chay/test (`@TempDir` cho test,
`target/run-<timestamp>/` cho benchmark).
