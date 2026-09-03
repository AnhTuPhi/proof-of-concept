# CONSISTENCY — Mo rong ra ngoai mot JVM

Companion cua [ISSUE.md](ISSUE.md) va [TECHNICAL.md](TECHNICAL.md).

POC nay dung va chinh xac tren **mot JVM**. Nhung LMAX Disruptor, theo dung
thiet ke cua no, la mot cau truc **trong-mot-tien-trinh** — ring buffer nam
trong heap cua 1 JVM, khong the "chia se" qua network nhu mot Kafka topic. Tai
lieu nay giai thich dieu do co nghia gi khi ban can xu ly nhieu hon mot JVM co
the dam duong, va phai thay doi nhung gi.

---

## 1. Nguyen nhan goc: moi thu deu la "trong 1 JVM"

```
RingBuffer<MarketEvent>     =  mang pre-allocate trong HEAP cua JVM nay
expectedNextSeq[]           =  mang trong HEAP cua JVM nay
journal.log, quarantine.log =  FILE tren DISK cua MAY nay
symbolStates (ConcurrentHashMap) = HEAP cua JVM nay
```

Chi co **mot** thanh phan khong nam trong JVM: bang `outbox` trong H2 — va do
la ly do no tro thanh diem phoi hop tu nhien khi mo rong (xem muc 4).

### Dieu gi xay ra neu chi don gian chay N instance

Neu ban khoi dong 3 instance cua ung dung nay tro toi CUNG mot outbox
database (chia se qua network), nhung moi instance van tu nhan ket noi
exchange gateway rieng:

```
                    +-------- Instance A --------+
gateway_1 ---------->| RingBuffer_A, Journal_A/A |
gateway_2 ---------->| symbolStates_A (VND, FPT) |----+
                    +----------------------------+    |
                    +-------- Instance B --------+     |     shared outbox DB
gateway_3 ---------->| RingBuffer_B, Journal_B/B |     +---> (OutboxDispatcher
                    | symbolStates_B (VND, HPG) |----+       tren moi instance,
                    +----------------------------+           an toan claim qua
                                                               FOR UPDATE)
```

Hai van de doc lap chong len nhau:

1. **`symbolStates` bi chia doi mot cach vo tinh.** Neu ca instance A va B
   deu nhan duoc message cua "VND" (vi du: 2 gateway session khac nhau cung
   gui tin ve VND, moi session dinh vao 1 instance), thi `SymbolState` cua
   VND ton tai **hai ban doc lap**, moi ban chi thay mot nua giao dich — VWAP
   sai, dem lenh sai. Sharding-theo-hash trong `BusinessLogicHandler`
   (muc 4, TECHNICAL.md) chi dam bao dung 1 worker so huu 1 ma **trong pham
   vi 1 JVM** — no khong biet gi ve JVM khac.
2. **`expectedNextSeq` (gap/duplicate detection) bi vo hieu khi session
   chuyen instance.** Neu mot gateway session ket noi lai va vo tinh (hoac co
   chu dich, do load balancer) roi vao instance khac, `IntegrityCheckHandler`
   cua instance moi khoi dong voi `expectedNextSeq = -1` cho session do — no
   se **chap nhan bat ky seq dau tien nao** ma khong bao gi bao gap, kien du
   thuc te co the da mat rat nhieu message trong luc chuyen instance. Day la
   loai loi **am tham** nguy hiem nhat: khong crash, khong log error, chi don
   gian la sai so lieu.

---

## 2. Nguyen tac sua: gan chat session voi instance, hoac chia se trang thai qua mot nguon ben ngoai

Co hai huong, tuong ung voi hai muc do dau tu khac nhau:

### Huong A — Session affinity + partition tinh (it thay doi nhat)

Neu kien truc mang (load balancer / FIX gateway router) co the dam bao **mot
exchange session luon gan voi dung 1 instance trong suot vong doi ket noi**
(gia dinh hop ly voi hau het FIX gateway — moi session la 1 TCP connection
ben vung), thi van de (2) o tren khong xay ra: seq tracking van dung vi
`expectedNextSeq` khong bao gio bi "mo lai tu dau" giua chung mot session con
song.

Van de (1) can them mot buoc: **phan chia ma chung khoan cho instance tu truoc**
(vi du: instance A phu trach nhom ma A-M, instance B phu trach N-Z), va dam
bao gateway router chi dinh tuyen dung nhom ma do toi dung instance. Day la
mo rong tu nhien cua sharding-theo-hash trong 1 JVM (TECHNICAL.md muc 4) len
cap instance — chi khac la partition map bay gio phai duoc cau hinh/dieu
phoi ben ngoai (service discovery, config), khong con la mot phep `hashCode()
% N` don gian trong code.

### Huong B — Externalise nguon feed qua mot log co thu tu, durable (khuyen nghi cho quy mo lon)

Thay vi moi instance tu mo ket noi rieng toi exchange gateway, dat toan bo
feed vao **mot Kafka topic, partition theo symbol**:

```
Exchange gateway (1 hoac vai ket noi, khong nhan ban)
        |
        v
   Kafka topic "market-events"  (partition theo hash(symbol))
        |            partition duoc Kafka dam bao: moi partition
        |            chi 1 consumer trong 1 consumer group xu ly
        +----------------+----------------+
        v                v                v
   Instance A        Instance B        Instance C
   (consume vai      (consume vai      (consume vai
    partition)         partition)        partition)
   RingBuffer_A       RingBuffer_B      RingBuffer_C
   (chi may du lieu   (chi may du lieu   (chi may du lieu
    cua ma no so huu)  cua ma no so huu)  cua ma no so huu)
```

Loi ich truc tiep:

- **Kafka partition thay the vai tro cua `hash(symbol) % workerCount`** —
  nhung o cap **giua cac instance**, khong chi giua cac thread trong 1 JVM.
  Kafka dam bao tai moi thoi diem, mot partition chi thuoc ve dung 1
  consumer trong group — chinh xac la invariant "1 ma chi 1 chu so huu" ma
  `BusinessLogicHandler` da dua ra o cap thread, gio duoc Kafka dam bao o cap
  process/instance.
- **Kafka offset thay the (hoac bo sung) `journal.log`** — offset la vi tri
  durable, co thu tu, co the seek lai — dung dieu ma journal file cuc bo dang
  co gang lam thu cong (ghi WAL + fsync) nhung khong the chia se qua nhieu
  may.
- **Rebalance khi instance chet/them moi** la tinh nang co san cua Kafka
  consumer group — khong can tu viet lai co che phan phoi ma nhu Huong A.
- `expectedNextSeq` co the bo hoan toan o tang ứng dung — Kafka da dam bao
  thu tu trong tung partition va khong mat message (voi cau hinh
  `acks=all`, replication phu hop) tot hon rat nhieu so voi TCP session don
  le ma POC dang mo phong.

---

## 3. Outbox: thanh phan DUY NHAT da san sang cho nhieu instance

Diem quan trong nhat cua thiet ke hien tai: **bang outbox khong can thay doi
gi de scale ra nhieu instance**, boi vi `claimBatch()` da dung
`SELECT ... FOR UPDATE` — moi dong chi co the bi 1 transaction claim tai 1
thoi diem, bat ke transaction do den tu thread nao, JVM nao, may nao. Chi can
tro nhieu instance cua `OutboxDispatcher` vao **cung mot connection string**
toi mot RDBMS server that (Oracle/Postgres — khong phai H2 embedded, xem gioi
han H2 o TECHNICAL.md muc 5), va chung se tu dong chia viec an toan,
khong dispatch trung mot dong hai lan.

Day la ly do outbox pattern duoc chon: no la mot **diem phoi hop tap trung
(coordination point)** ma khong doi hoi ung dung phai tu xay dung giao thuc
phan tan rieng (nhu leader election hay distributed lock) chi de dispatch
message an toan.

---

## 4. Muc phoi hop kem chac chan hon: crash-restart va journal

Neu mot instance crash va restart, journal file cuc bo cua no (`journal.log`)
chi co the tra loi cau hoi "instance nay da nhan nhung gi truoc khi chet" —
no khong biet gi ve cac instance khac. Trong Huong A (session affinity),
day la du: instance restart, doc lai journal cua chinh no, biet duoc minh da
xu ly toi dau, roi noi lai ket noi voi cung tap session ma no phu trach truoc
do.

Trong Huong B (Kafka), day tro thanh mot bai toan da duoc giai san: Kafka
consumer group tu luu **offset da commit** (trong chinh Kafka, khong phai
file cuc bo) — instance restart chi can doc lai offset da commit tu Kafka va
tiep tuc, khong can tu quan ly journal file nua. Day la ly do Huong B duoc
khuyen nghi cho quy mo lon: no bien "moi instance tu quan ly durability rieng"
thanh "mot he thong (Kafka) quan ly durability chung cho tat ca".

---

## 5. Bang tom tat: migration checklist

| # | Thay doi | Giai quyet |
|---|---|---|
| 1 | Co dinh session-to-instance affinity (Huong A, don gian) HOAC dua feed qua Kafka partition-theo-symbol (Huong B, quy mo lon) | `symbolStates` bi chia doi vo tinh, `expectedNextSeq` bi reset sai khi session doi instance |
| 2 | Neu chon Huong B: bo `expectedNextSeq` thu cong, dua vao Kafka offset + partition assignment | Don gian hoa, dung co che da duoc kiem chung o quy mo lon thay vi tu xay |
| 3 | Neu chon Huong B: journal file cuc bo co the bo hoac chi giu nhu cache tam thoi — Kafka offset la nguon durable chinh | Crash-restart khong can doc lai journal file cuc bo |
| 4 | Chuyen outbox tu H2 embedded sang RDBMS server that (Oracle/Postgres) dung chung cho moi instance | `claimBatch()` da san sang cho nhieu instance — chi can DB that ho tro tai |
| 5 | Outbox nen nam CHUNG schema/transaction voi DB nghiep vu that (xem TECHNICAL.md muc 5) | Dam bao "cap nhat state" va "ghi outbox" cung commit/rollback |
| 6 | Dispatcher: bo `claimLock` trong-JVM (chi can khi 1 JVM chay nhieu dispatcher thread tren H2 embedded) — voi RDBMS server that va nhieu instance, `FOR UPDATE` da du an toan | Don gian hoa code, dung dung co che DB-level cho truong hop nhieu process |

**Tu duy cot loi:** Disruptor giai bai toan "throughput cao trong 1 JVM" cuc
ky tot — dung noi rong no ra thanh mot "distributed ring buffer" (khong ton
tai va khong nen ton tai). Thay vao do, dat NHIEU instance Disruptor doc lap
canh nhau, moi instance so huu mot phan du lieu ro rang (theo session hoac
theo Kafka partition), va dung MOT nguon durable/coordination o giua (Kafka
cho feed vao, RDBMS outbox cho message di ra) de ket noi chung lai thanh mot
he thong nhat quan.
