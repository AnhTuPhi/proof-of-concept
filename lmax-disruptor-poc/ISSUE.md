# ISSUE — Xu ly hang trieu market event tu so giao dich chung khoan

## 1. Boi canh

He thong nhan feed du lieu tu so giao dich (gia khop lenh, xac nhan lenh,
quote) qua nhieu ket noi gateway song song. O thi truong that, luu luong nay
co the dat **hang trieu message/giay** vao gio cao diem (mo/dong cua, tin tuc
vi mo), va moi message deu can duoc:

- **nhan het** — khong duoc roi message nao du gateway bi nghen, retransmit,
  hay JVM bi GC pause;
- **xu ly het, dung mot lan** — khong duoc bo sot, khong duoc xu ly trung
  (vi du: cong don khoi luong giao dich hai lan cho cung mot lenh);
- **toan ven** — du lieu khong bi hong trong qua trinh truyen/xu ly (bit flip,
  decode sai, torn write);
- **an toan da luong (thread-safe)** — nhieu gateway ket noi song song, nhieu
  worker xu ly song song theo ma chung khoan, nhung khong duoc dùng lock chan
  ngang lam nghen throughput;
- **khong mat message khi downstream loi** — he thong ha nguon (Kafka, gateway
  clearing, dich vu xac nhan cho client) co the tam thoi loi/cham; message
  phai duoc **retry co backoff**, va neu retry het so lan cho phep thi phai
  **hien ra ro rang** (dead-letter) thay vi bien mat.

## 2. Cau hoi dat ra

> Lam sao xay mot pipeline xu ly su kien (event processing pipeline) chay
> tren mot JVM, dat throughput hang trieu event/giay, ma van giu duoc tinh
> toan ven, tinh day du (khong mat, khong trung), va co co che phuc hoi loi
> ro rang, co the kiem chung duoc bang so lieu chu khong phai bang cam tinh?

## 3. Vi sao kho

1. **Hang doi thong thuong (BlockingQueue, ArrayBlockingQueue) qua cham** o
   quy mo trieu event/giay — moi lan `put`/`take` deu co lock/CAS tren cung
   mot bien dem, va GC churn tu viec boxing/allocate object cho moi message
   la mot nguon do tre khong the doan truoc (GC pause vai chuc ms lam mat
   hang tram nghin event trong hang doi).
2. **Nhieu giai doan xu ly noi tiep** (kiem tra toan ven -> ghi nhat ky -> xu
   ly nghiep vu -> day ra outbox) can duoc noi voi nhau ma khong dung hang
   doi trung gian rieng cho tung cap giai doan (moi hang doi la mot diem
   allocate + lock rieng).
3. **Song song hoa xu ly nghiep vu theo ma chung khoan** phai giu dung thu
   tu cho tung ma (VND phai duoc xu ly theo dung thu tu no den), nhung van
   phai chay da luong tren nhieu core cho cac ma khac nhau — can mot co che
   sharding khong dung lock.
4. **Ghi nhat ky (durability) và ghi outbox (transactional messaging)** deu
   la thao tac I/O — cham hon nhieu so voi xu ly trong bo nho. Neu fsync/ghi
   DB tren tung event rieng le, throughput sup do; neu gom batch qua tho thi
   mat qua nhieu du lieu khi crash.
5. **Downstream khong dang tin cay 100%.** Retry ngay lap tuc (khong backoff)
   khi downstream dang qua tai chi lam no qua tai them (retry storm). Nhung
   retry mai khong dung cung khong on — can gioi han so lan va mot noi de
   "message loi vinh vien" duoc nhin thay (khong roi vao hu vo).

## 4. Pham vi POC

Repo nay dung **LMAX Disruptor** — ring buffer lock-free duoc thiet ke rieng
cho bai toan nay (san sinh boi chinh san giao dich LMAX de xu ly lenh giao
dich voi do tre cuc thap) — de xay mot pipeline 4 giai doan:

```
ExchangeFeedSimulator (N gateway session, N thread producer)
        |
        v
   RingBuffer<MarketEvent>           <- lock-free, pre-allocated
        |
        v
 IntegrityCheckHandler   (1)         <- checksum + phat hien gap/duplicate
        |
        v
 JournalHandler          (1)         <- ghi WAL (write-ahead log), fsync theo nhom
        |
        v
 BusinessLogicHandler[]  (N)         <- song song theo shard ma chung khoan
        |
        v
 OutboxHandler           (1)         <- ghi outbox (H2) theo batch JDBC
```

Va mot **OutboxDispatcher** chay tren thread rieng, tach biet hoan toan khoi
Disruptor, thuc hien retry/backoff (full-jitter exponential) va dead-letter
khi het luot retry.

Xem [TECHNICAL.md](TECHNICAL.md) de biet chi tiet thiet ke tung giai doan,
[CONSISTENCY.md](CONSISTENCY.md) de biet cach mo rong ra nhieu instance/JVM,
va [docs/TEST_PLAN.md](docs/TEST_PLAN.md) + [docs/PERFORMANCE.md](docs/PERFORMANCE.md)
cho ke hoach kiem thu va so lieu hieu nang do duoc thuc te tren may chay POC nay.

## 5. Ngoai pham vi (co y thuc bo qua)

- Ket noi that toi mot san giao dich / FIX gateway / Kafka that — tat ca deu
  mo phong (`ExchangeFeedSimulator`, `FlakyDownstreamPublisher`).
- Outbox table nam trong H2 rieng, khong chung transaction voi mot DB nghiep
  vu that (Oracle trong DAccount se la vi du that) — xem gioi han nay trong
  TECHNICAL.md.
- Order book / matching engine day du — `SymbolState` chi la vi du toi gian
  (dem so lenh, khoi luong, VWAP) de minh hoa sharding, khong phai order
  book that.
- Bao mat, xac thuc gateway, ma hoa payload.

## 6. Definition of done

- [x] Ring buffer + 4-giai-doan handler chain chay duoc, thread-safe, khong
      dung lock tren hot path.
- [x] Checksum + gap/duplicate detection phat hien duoc loi da cai vao (kiem
      chung bang test).
- [x] Reconciliation report chung minh bang so: nhan du == produced, xu ly du
      == journaled tru duplicate, outbox du == processed.
- [x] Retry/backoff/outbox/dead-letter chay duoc voi downstream gia lap loi
      ngau nhien, kiem chung bang test (khong double-dispatch, dead-letter
      dung so lan).
- [x] Benchmark do duoc throughput/latency thuc te tren may chay POC, khong
      chi trich dan so lieu ly thuyet cua LMAX.
