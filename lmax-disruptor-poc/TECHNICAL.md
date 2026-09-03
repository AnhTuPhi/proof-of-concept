# TECHNICAL — Thiet ke pipeline LMAX Disruptor

Companion cua [ISSUE.md](ISSUE.md) (bai toan) va [CONSISTENCY.md](CONSISTENCY.md)
(mo rong nhieu instance). Tai lieu nay giai thich, **theo tung giai doan**:

1. hard problem no phai giai,
2. dang bao ve cai gi,
3. hinh dang giai phap,
4. key tech theo trach nhiem,
5. tung sub-problem duoc giai the nao trong code,
6. tech debt chap nhan co y thuc.

---

## 0. Vi sao LMAX Disruptor, khong phai BlockingQueue

`java.util.concurrent.ArrayBlockingQueue` giai quyet dung bai toan
producer-consumer, nhung o quy mo trieu event/giay no co hai chi phi an:

- **Lock tren moi `put`/`take`** — dung ReentrantLock + Condition, nghia la
  moi thao tac deu co the phai park/unpark thread qua he dieu hanh.
- **False sharing giua head va tail pointer** — hai bien dem doc/ghi lien tuc
  boi hai thread khac nhau, nam tren cung cache line, khien CPU phai lam
  tuoi cache line lien tuc giua cac core (cache coherency traffic).

Disruptor giai ca hai:

- **Khong lock tren hot path.** Producer xin mot slot bang CAS tren
  `Sequence` (qua `RingBuffer.next()`), khong phai lock. Consumer doc
  `Sequence` cua producer (mot volatile read) de biet co du lieu moi chua —
  day la mot **memory barrier tu nhien**, khong can `synchronized`.
- **Pre-allocate toan bo slot khi khoi dong** (`MarketEventFactory`), nen
  publish = mutate-in-place, khong tao object moi -> khong tao rac cho GC
  tren hot path. Day la ly do latency duoi tai cao van on dinh (khong bi GC
  pause lam nhieu p99).
- **Padding chong false sharing.** Cac lop noi bo cua Disruptor (`Sequence`,
  cac ring buffer entry) duoc dem cache-line — dieu ma tu viet tay rat de
  lam sai.

Doi lai: Disruptor doi hoi tu duy khac — kich thuoc ring buffer phai la luy
thua cua 2, va cach noi nhieu consumer voi nhau (`handleEventsWith().then()`)
phai duoc thiet ke truoc, khong linh hoat them consumer luc runtime nhu mot
topic pub/sub thong thuong.

---

## 1. RingBuffer + MarketEvent — nen tang thread-safe

`event/MarketEvent.java`, `event/MarketEventFactory.java`,
`ingest/ExchangeFeedSimulator.java`

### Hard problem
Nhieu producer thread (moi thread la mot exchange gateway session) phai
publish dong thoi vao CUNG mot ring buffer ma khong dam len nhau, va nhieu
consumer thread phai doc dung slot ma khong doc du lieu chua duoc publish
xong (torn read).

### Dang bao ve gi
Tinh nhat quan cua tung slot trong ring buffer duoi ap luc ghi da luong.

### Giai phap
`ProducerType.MULTI` (trong `DisruptorPipeline`) bat Disruptor dung
`MultiProducerSequencer` — dung CAS tren cursor de nhieu producer thread
"xep hang" xin slot an toan. Publish = `ringBuffer.next()` (CAS xin sequence)
-> `ringBuffer.get(seq)` (lay slot da duoc pre-allocate) -> mutate field cua
`MarketEvent` tai cho -> `ringBuffer.publish(seq)` (volatile write bao consumer
biet slot da san sang).

### Key tech theo trach nhiem
| Trach nhiem | Co che |
|---|---|
| Nhieu producer an toan | `ProducerType.MULTI` -> `MultiProducerSequencer` (CAS) |
| Khong allocate tren hot path | `MarketEventFactory` pre-allocate toan bo slot luc `disruptor.start()` |
| Bao consumer slot san sang | `RingBuffer.publish(seq)` — volatile write |
| Phat hien tuong thich cache-line | Disruptor tu padding cac field `Sequence` noi bo |

### Tech debt
- `MarketEvent` la mutable, tai su dung — bat ky code nao giu tham chieu ra
  ngoai onEvent() (vi du: dua vao mot List de xu ly bat dong bo) se doc phai
  du lieu bi ghi de boi event tiep theo. Tat ca handler trong POC nay xu ly
  xong trong chinh `onEvent()`, dung quy tac.
- Kich thuoc ring buffer co dinh luc khoi dong — khong resize runtime. Neu
  consumer cham hon producer qua lau, producer se bi block o `next()` (day
  la co che back-pressure tu nhien cua Disruptor, khong phai bug).

---

## 2. IntegrityCheckHandler — toan ven + nhan du

`pipeline/IntegrityCheckHandler.java`

### Hard problem
Lam sao biet mot event bi hong (bit flip, decode sai) truoc khi no anh huong
den logic nghiep vu? Va lam sao biet feed co bi mat message giua chung (packet
loss) hay bi gui trung (redelivery)?

### Dang bao ve gi
Tinh toan ven du lieu (integrity) va tinh day du cua luong nhan (completeness
— "nhan du").

### Giai phap
Producer tinh checksum FNV-1a 64-bit tren cac field bat bien cua event ngay
khi publish (`MarketEvent.set()` goi `computeChecksum()` va luu vao
`expectedChecksum`). Day la giai doan **dau tien** trong chain — no tinh lai
checksum va so sanh; neu lech, danh dau `poisoned` va moi giai doan sau deu
bo qua event nay (chi ghi vao quarantine log). Song song, no theo doi
`exchangeSeq` — so thu tu ma chinh gateway gan cho message — theo tung
session, de phat hien gap (seq nhay vot = mat message) va duplicate (seq lui
lai = gui trung).

### Key tech theo trach nhiem
| Trach nhiem | Co che |
|---|---|
| Phat hien hong | `Checksums.fnv1a64(...)` tinh lai, so sanh voi checksum luu tu luc publish |
| Phat hien mat message | Mang `expectedNextSeq[sessionId]`, so sanh voi `exchangeSeq` moi event |
| Phat hien gui trung | `seq < expected` -> `markDuplicate()` |
| Single-writer, khong lock | Chi 1 instance handler, Disruptor dam bao no nhan event **tuan tu**, nen mang `expectedNextSeq` khong can dong bo |

### Cach giai tung sub-problem
- **Toan ven** — checksum FNV-1a rieng cho tung event, tinh tren field bat
  bien (exchangeSeq, sessionId, symbol, type, price, quantity, side) — dovoi
  do phuc tap O(1) khong anh huong throughput dang ke.
- **Nhan du (khong mat)** — gap duoc tinh chinh xac bang so (`seq - expected`),
  khong chi flag boolean — nen biet duoc **mat bao nhieu** message, du de
  metric hoa va len ke hoach yeu cau retransmit that trong production.
- **Khong xu ly trung** — event trung duoc danh dau `duplicate` de cac giai
  doan sau (Journal, BusinessLogic, Outbox) biet ma bo qua dung 1 lan (xem
  `ReconciliationReport` de biet dung contract dem).

### Tech debt
- Gap chi duoc **phat hien va do luong** (metric), khong co co che thuc su
  yeu cau retransmit tu exchange (trong production that se can goi lai FIX
  gateway hoac doc lai tu nhat ky cua san giao dich).
- `expectedNextSeq` la mot mang co dinh kich thuoc `maxSessions` — session id
  vuot qua se ArrayIndexOutOfBounds. Chap nhan duoc cho POC (so session biet
  truoc), production can `Long2LongOpenHashMap` hoac tuong duong.

---

## 3. JournalHandler — write-ahead log, xu ly du (durability)

`pipeline/JournalHandler.java`

### Hard problem
Neu JVM crash giua chung, lam sao biet duoc nhung message nao da "chac chan
duoc he thong nhan" (co the replay lai) va message nao chua? Ghi disk sau moi
event (fsync) thi dung nhung cham toi muc vo dung o quy mo trieu event/giay.

### Dang bao ve gi
Kha nang phuc hoi (crash recovery) — "xu ly du" theo nghia: du crash, khong
mat du lieu da duoc xac nhan nhan.

### Giai phap
Gom nhieu dong log vao bo nho (`StringBuilder pending`), chi `force()`
(fsync that su) khi Disruptor bao `endOfBatch == true` — tuc la khi batch tu
nhien cua Disruptor ket thuc. Duoi tai cao, Disruptor tu gop nhieu event lai
thanh 1 batch lon truoc khi consumer kip xu ly, nen fsync it di dung luc
throughput can no nhat.

### Key tech theo trach nhiem
| Trach nhiem | Co che |
|---|---|
| Gom batch truoc khi ghi | `StringBuilder pending`, flush khi `endOfBatch` |
| Durability that su | `FileChannel.force(false)` — ep he dieu hanh ghi xuong dia, khong chi vao page cache |
| Tach event hong | Poisoned event ghi vao `quarantineChannel` rieng, khong lan vao journal chinh |
| Single writer | 1 instance, chi 1 thread Disruptor dung den — khong can lock |

### Cach giai tung sub-problem
- **Durability vs throughput** — fsync theo nhom batch (group commit), khong
  theo tung event. Cai gia phai tra: toi da mat **1 batch chua fsync** neu
  crash dung giua hai batch — day la trade-off pho bien cua moi he thong WAL
  thuc te (Kafka, Postgres WAL cung group-commit tuong tu).
- **Batch qua nho lam fsync qua nhieu** — day la mot bay thuc te da gap phai
  khi benchmark: neu consumer luon "duoi kip" producer (endOfBatch=true tren
  gan het event), moi batch chi co 1-2 event, dan den fsync-per-event va
  throughput sup do (~vai nghin event/giay thay vi hang tram nghin). Xem
  [docs/PERFORMANCE.md](docs/PERFORMANCE.md) phan "group commit" de biet so
  lieu that va cach tune.

### Tech debt
- Journal la file text don gian (pipe-delimited), khong co the tu dong phat
  hien torn write o cuoi file sau crash (production can length-prefixed
  record hoac CRC tren tung dong).
- Khong co co che nen/xoay vong file (log rotation) — file lon dan vo han
  theo thoi gian chay.
- Chua co code doc lai journal de replay sau crash (chi ghi, chua doc) — day
  la buoc tiep theo ro rang neu bien POC nay thanh production.

---

## 4. BusinessLogicHandler — song song theo shard, khong lock

`pipeline/BusinessLogicHandler.java`, `pipeline/SymbolState.java`

### Hard problem
Xu ly nghiep vu (cap nhat vi the/order book theo ma chung khoan) can chay
song song tren nhieu core de theo kip throughput, nhung phai giu **dung thu
tu** cho tung ma rieng le (VND phai xu ly theo dung thu tu no den, khong duoc
dao lon).

### Dang bao ve gi
Thong luong (throughput qua nhieu core) VA tinh dung dan cua thu tu xu ly
theo tung ma (per-symbol ordering) — cung mot luc, khong danh doi cai nay
lay cai kia.

### Giai phap
N instance handler chay song song (`.handleEventsWith(a, b, c, ...)` trong
`DisruptorPipeline`), nhung **moi instance nhin thay MOI sequence** — Disruptor
khong tu chia ring buffer ra cho tung handler. Moi instance tu loc:
`hash(symbol) % N == workerIndex`, bo qua neu khong phai cua minh. Ket qua:
tat ca event cua "VND" luon roi vao dung 1 worker, theo dung thu tu chung
duoc publish — nhung "FPT" co the duoc xu ly boi worker khac, song song that
su tren core khac.

### Key tech theo trach nhiem
| Trach nhiem | Co che |
|---|---|
| Chia viec khong lock | `Math.floorMod(symbol.hashCode(), workerCount) == workerIndex` — moi worker tu loc |
| Giu thu tu theo ma | Sharding theo symbol, khong theo Disruptor sequence — moi ma luon thuoc dung 1 worker |
| State an toan da luong | `ConcurrentHashMap<String, SymbolState>` dung chung — an toan vi cau truc, khong vi tung field (xem duoi) |

### Cach giai tung sub-problem
- **Khong lock giua cac worker** — vi sharding dam bao 2 worker khong bao
  gio cung ghi vao 1 `SymbolState`, nen ban than `SymbolState` khong can field
  nao la atomic/volatile — no CHI an toan nho **invariant sharding**, khong
  phai nho dong bo hoa. `ConcurrentHashMap` chi can thiet de bao ve cau truc
  noi bo (bucket, resize) khi nhieu worker `computeIfAbsent` cho cac KHOA
  KHAC NHAU cung luc — do la thao tac an toan cua ConcurrentHashMap theo
  thiet ke.
- **Bo qua dung nhung gi can bo qua** — poisoned va duplicate event bi skip
  o day (tranh cong don sai vi the/khoi luong).

### Tech debt
- Neu mot ma (vi du VND vao gio cao diem) chiem toan bo luu luong, worker so
  huu VND se la bottleneck du cac worker khac ranh — day la gioi han co huu
  cua sharding theo hash tinh (static sharding). Production co the can
  sharding dong (rebalance) hoac chia nho hon theo (symbol, price-band).
- `SymbolState` chi la vi du toi gian (dem lenh, VWAP) — khong phai order
  book that voi depth-of-book.

---

## 5. OutboxHandler + OutboxStore + OutboxDispatcher — outbox pattern, retry/backoff

`pipeline/OutboxHandler.java`, `outbox/OutboxStore.java`,
`outbox/OutboxDispatcher.java`, `outbox/RetryBackoffPolicy.java`

### Hard problem
Sau khi xu ly nghiep vu xong, ket qua phai duoc **cong bo ra ngoai** (xac
nhan cho client, day len Kafka, bao clearing system) — nhung he thong nhan
(downstream) co the tam thoi khong san sang. Khong duoc chan pipeline chinh
lai de cho downstream, nhung cung khong duoc mat message neu downstream loi.

### Dang bao ve gi
"At-least-once delivery" cho message di ra ngoai, ma khong lam nghen ring
buffer, va khong retry vo han khi downstream that su hong.

### Giai phap — Transactional Outbox pattern
Thay vi goi truc tiep downstream tu trong Disruptor consumer thread,
`OutboxHandler` (giai doan cuoi trong chain) chi **ghi mot dong ghi cho vao
bang `outbox`** (trang thai `PENDING`), theo batch JDBC — khong goi mang.
Mot thanh phan hoan toan tach biet, `OutboxDispatcher`, chay tren thread
rieng, **doc** bang outbox va thuc su goi downstream:

```
Disruptor consumer thread          OutboxDispatcher thread(s) (tach biet)
        |                                      |
   INSERT outbox (PENDING)              SELECT ... FOR UPDATE (claim)
        |                                      |
   (khong cho downstream)              publish() toi downstream
                                               |
                                    thanh cong -> DISPATCHED
                                    that bai   -> PENDING + backoff, hoac
                                                  DEAD_LETTER neu het luot retry
```

Day la ly do pipeline chinh khong bao gio bi cham lai boi mot downstream
cham/loi — hai the gioi tach biet hoan toan qua bang outbox.

### Key tech theo trach nhiem
| Trach nhiem | Co che |
|---|---|
| Tach ghi outbox khoi goi downstream | `OutboxHandler` chi INSERT, `OutboxDispatcher` moi goi `DownstreamPublisher` |
| Claim an toan da tien trinh | `SELECT ... FOR UPDATE` trong 1 transaction, flip sang `IN_FLIGHT` truoc commit |
| Backoff khong gay retry storm | `RetryBackoffPolicy` — full jitter exponential (AWS Architecture Blog) |
| Gioi han retry, khong mat am tham | Het `maxAttempts` -> `DEAD_LETTER`, trang thai hien, khong bi xoa |
| Batch insert hieu qua | `PreparedStatement.addBatch()`/`executeBatch()`, commit theo `endOfBatch` |

### Cach giai tung sub-problem
- **Khong chan Disruptor bang I/O mang** — `OutboxHandler` chi noi chuyen voi
  DB nhung (embedded H2, rat nhanh) trong chinh JVM, khong bao gio goi mang
  ra ngoai. Goi mang that su (downstream that co the cham/loi) hoan toan nam
  o `OutboxDispatcher`, tren thread khac.
- **Khong retry-storm** — `RetryBackoffPolicy.nextDelay(attempt)` dung cong
  thuc *full jitter*: `delay = random(0, min(cap, base * 2^attempt))`. Neu
  hang tram message cung that bai mot luot (downstream sap), backoff khong
  jitter se khien tat ca cung thu lai dung 1 thoi diem — full jitter rai deu
  chung ra, giam tai dinh diem cho downstream dang phuc hoi.
- **Khong retry vo han** — `maxAttempts` (mac dinh 5) — qua nguong chuyen
  `DEAD_LETTER`, mot trang thai **hien va truy van duoc**
  (`OutboxStore.countByStatus("DEAD_LETTER")`), khac han voi "roi vao hu vo
  sau N lan thu".
- **An toan khi chay nhieu dispatcher / nhieu instance** — `claimBatch()`
  dung row lock cap DB (`FOR UPDATE`), nen hai dispatcher (kha nang chay tren
  hai instance/pod khac nhau trong production that) khong the cung claim
  mot dong — duoc kiem chung boi
  `OutboxDispatcherTest.concurrentDispatchersNeverDoubleDispatchTheSameRow`.

### Tech debt (quan trong — doc ky truoc khi ap dung production)
- **Outbox nam trong H2 rieng, KHONG chung transaction voi DB nghiep vu.**
  Diem cot loi cua outbox pattern that su la: "cap nhat state" va "ghi outbox"
  phai **cung 1 transaction cuc bo** voi DB nghiep vu, de dam bao ca hai cung
  commit hoac cung rollback. POC nay tach outbox ra H2 rieng de don gian hoa
  — nghia la **giua buoc business-logic (in-memory) va buoc ghi outbox van co
  mot khoang ho** ly thuyet neu JVM crash dung giua hai buoc. Trong DAccount
  (Oracle), bang outbox nen nam CHUNG schema/transaction voi bang nghiep vu
  that (vi du bang account/statement), va INSERT outbox phai nam trong CUNG
  transaction JDBC voi UPDATE nghiep vu.
- **In-process lock cho buoc claim** (xem `OutboxDispatcher.claimLock`) —
  trong khi phat trien POC nay, chay nhieu dispatcher thread cung tranh
  `SELECT ... FOR UPDATE` tren H2 file-mode voi khoi luong lon (~100k+ dong,
  nhieu retry) da gay ra loi noi bo cua H2 MVStore (deadlock/loi doc-ghi file)
  — mot gioi han cua H2 embedded, khong phai loi thiet ke outbox. Da fix bang
  cach serialize buoc claim trong CUNG mot JVM bang `synchronized`; DB-level
  row lock (`FOR UPDATE`) van la thu that su can thiet khi co **nhieu
  instance/process** khac nhau cung claim (xem [CONSISTENCY.md](CONSISTENCY.md)).
  Voi mot RDBMS production-grade (Oracle, Postgres) o quy mo lon hon, day
  khong con la van de.
- Chua co idempotency key phia downstream that su (vi du Kafka message key)
  — "at-least-once" tu outbox co nghia downstream co the nhan trung neu
  dispatcher crash giua luc publish thanh cong va truoc khi kip
  `markDispatched` — downstream that can tu idempotent hoa theo key nghiep vu.
- **`claimBatch()` tung co `ORDER BY id` — gay do phuc tap gan O(n^2) o quy mo
  trieu dong.** Phat hien khi benchmark 8 trieu event: dispatch cham dan mot
  cach bat thuong, jstack cho thay dispatcher thread dang ban ram doc file
  MVStore. Nguyen nhan: sap xep theo `id` (khoa chinh) buoc H2 phai lan luot
  bo qua tung dong DA claim (khong con PENDING) nam truoc cac dong PENDING con
  lai trong thu tu id — cang ve sau, cang nhieu dong phai bo qua moi lan goi
  `claimBatch`. Fix: bo `ORDER BY id`, de H2 dung thang index
  `(status, next_attempt_at)` ma khong can gop voi sap xep theo khoa chinh.
  Thu tu dispatch giua cac dong khong phai la yeu cau dung dan (moi dong tu
  quan ly retry/backoff doc lap), nen bo sap xep khong mat gi ve mat logic —
  chi la mot bay hieu nang khi viet SQL "tu nhien" (them ORDER BY cho de doc)
  ma khong nghi den ke hoach thuc thi cua no o quy mo lon. Xem so lieu truoc/
  sau fix o [docs/PERFORMANCE.md](docs/PERFORMANCE.md).

---

## 6. Tong hop tech debt xuyen suot {#tech-debt}

| Debt | Anh huong | O dau |
|---|---|---|
| Outbox khac transaction voi DB nghiep vu | Khoang ho ly thuyet giua business-logic va outbox khi crash | OutboxHandler, muc 5 |
| Journal la text don gian, khong CRC tung dong | Kho phat hien torn write cuoi file sau crash | JournalHandler, muc 3 |
| Khong co code replay journal | Chua chung minh duoc "phuc hoi that su" sau crash, moi la ghi | JournalHandler, muc 3 |
| Sharding tinh theo hash(symbol) | Ma "hot" chiem 1 worker co the thanh bottleneck | BusinessLogicHandler, muc 4 |
| `expectedNextSeq` la mang co dinh | Gioi han so session tai da biet truoc | IntegrityCheckHandler, muc 2 |
| Khong co FIX gateway / Kafka that | Toan bo la mo phong, chua do duoc do tre mang that | ExchangeFeedSimulator, FlakyDownstreamPublisher |
| Downstream chua idempotent that su | At-least-once co the trung neu dispatcher crash dung luc | OutboxDispatcher, muc 5 |

Day la nhung diem chuyen tu POC sang production can lam tiep, khong phai
thieu sot bi bo qua — moi diem deu co ly do va duoc ghi lai co y thuc.
