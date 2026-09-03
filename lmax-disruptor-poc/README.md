# lmax-disruptor-poc — Java 21 + LMAX Disruptor 4.0

POC xu ly hang trieu market event (gia khop lenh, quote, xac nhan lenh) tu
mot san giao dich chung khoan, dung **LMAX Disruptor** lam ring buffer
lock-free trung tam. Muc tieu: thread-safe, toan ven du lieu, nhan du/xu ly
du (co the kiem chung bang so, khong chi bang loi noi), va co co che
retry/backoff/outbox de xu ly loi downstream.

## 📚 Documentation

| Doc | Noi dung |
|---|---|
| [ISSUE.md](ISSUE.md) | Bai toan can giai: vi sao hang doi thong thuong khong du, cac rang buoc (toan ven, nhan du, xu ly du, thread-safe, retry co gioi han). |
| [TECHNICAL.md](TECHNICAL.md) | Voi **tung giai doan pipeline**: hard problem, dang bao ve gi, giai phap, key tech, tech debt. |
| [CONSISTENCY.md](CONSISTENCY.md) | Vi sao Disruptor la cau truc trong-1-JVM, va can thay doi gi de chay nhieu instance. |
| [docs/TEST_PLAN.md](docs/TEST_PLAN.md) | Ke hoach kiem thu: unit, correctness/reconciliation, concurrency stress, chaos (retry/backoff/dead-letter), benchmark. |
| [docs/PERFORMANCE.md](docs/PERFORMANCE.md) | So lieu throughput/latency **do thuc te** tren may chay POC nay — khong chi trich dan so lieu ly thuyet cua LMAX. |

## Stack

- Java **21** (record, pattern matching, virtual threads san sang dung neu can)
- **LMAX Disruptor 4.0.0** — ring buffer lock-free trung tam
- **H2 2.4** (embedded, file-mode) + **HikariCP 7** — outbox table
- **HdrHistogram** — do latency percentile chinh xac
- **JUnit 5** — unit test, correctness test, concurrency stress test
- **SLF4J + Logback** — logging

## Kien truc

```
ExchangeFeedSimulator (N gateway session, N thread producer, MULTI producer sequencer)
        |
        v
   RingBuffer<MarketEvent>            <- lock-free, pre-allocated, khong tao rac
        |
        v
 IntegrityCheckHandler   (1)          <- checksum FNV-1a + phat hien gap/duplicate theo session
        |
        v
 JournalHandler          (1)          <- ghi WAL, fsync theo nhom batch (group commit)
        |
        v
 BusinessLogicHandler[]  (N)          <- song song theo shard hash(symbol), khong lock
        |
        v
 OutboxHandler           (1)          <- ghi outbox (H2) theo batch JDBC, cung 1 lan voi journal
```

Va tach biet hoan toan khoi Disruptor:

```
OutboxDispatcher (M thread doc lap, moi thread giu 1 connection rieng)
        |
        v
   claim (SELECT...FOR UPDATE) -> publish() toi downstream -> DISPATCHED
                                                            -> that bai: PENDING + full-jitter backoff
                                                            -> het luot retry: DEAD_LETTER
```

## Chay thu

```bash
./mvnw -q -DskipTests package
```

```bash
# Demo nho, de doc, co inject loi (drop/duplicate/corrupt + downstream flaky)
java -jar target/lmax-disruptor-poc.jar mode=demo

# Benchmark throughput/latency thuc, happy path
java -jar target/lmax-disruptor-poc.jar mode=benchmark sessions=8 eventsPerSession=1000000 waitStrategy=yielding

# Chaos: bat het cac loai loi + downstream that thuong loi 30%
java -jar target/lmax-disruptor-poc.jar mode=chaos
```

Tham so co san (tat ca deu co gia tri mac dinh hop ly, xem
`BenchmarkRunner.main`): `sessions`, `eventsPerSession`, `ringBufferSize`,
`businessWorkers`, `waitStrategy` (`busy_spin|yielding|sleeping|blocking`),
`outboxWorkers`, `outboxBatchSize`, `dropRate`, `duplicateRate`,
`corruptRate`, `downstreamFailureRate`.

## Chay test

```bash
./mvnw -q test
```

73 test, bao gom:
- Unit test cho checksum/gap/duplicate detection (`IntegrityCheckHandlerTest`)
  va backoff policy (`RetryBackoffPolicyTest`, 53 test voi `@RepeatedTest` vi
  backoff co jitter ngau nhien).
- Correctness end-to-end voi day du loai loi bat dong thoi
  (`PipelineReconciliationTest`) — chung minh bang so **chinh xac tung don
  vi**, khong chi "hau nhu dung".
- Stress da luong lap lai 10 lan (`ConcurrencyStressTest`) — moi lan mot
  ring buffer moi, nhieu producer + nhieu business worker, kiem tra dem dung
  tuyet doi moi lan (khong flaky).
- Outbox dispatcher: thanh cong ngay, retry-roi-thanh-cong, het-retry-thanh-
  dead-letter, va nhieu dispatcher khong bao gio dispatch trung 1 dong
  (`OutboxDispatcherTest`).

Xem [docs/TEST_PLAN.md](docs/TEST_PLAN.md) de biet dung ke hoach kiem thu day du
(bao gom nhung gi CHUA tu dong hoa duoc va vi sao).

## Source tree

```
src/main/java/vn/com/poc/disruptor/
├── bench/         BenchmarkRunner (CLI), LatencyRecorder (HdrHistogram)
├── event/         MarketEvent, EventType, MarketEventFactory
├── ingest/        ExchangeFeedSimulator, FeedConfig
├── pipeline/       DisruptorPipeline, PipelineConfig, WaitStrategies,
│                   IntegrityCheckHandler, JournalHandler,
│                   BusinessLogicHandler, SymbolState, OutboxHandler
├── outbox/        OutboxStore (H2/HikariCP), OutboxRecord, OutboxStatus,
│                   OutboxDispatcher, RetryBackoffPolicy,
│                   DownstreamPublisher, FlakyDownstreamPublisher
├── metrics/       PipelineMetrics, ReconciliationReport
└── util/          Checksums
```

## Ket qua do duoc (tom tat — chi tiet o docs/PERFORMANCE.md)

Xem [docs/PERFORMANCE.md](docs/PERFORMANCE.md) cho bang so day du, phuong
phap do, va phan tich bottleneck (journal fsync cadence la yeu to quyet dinh
lon nhat, khong phai ban than ring buffer).
