package vn.com.poc.disruptor.ingest;

import com.lmax.disruptor.RingBuffer;
import vn.com.poc.disruptor.event.EventType;
import vn.com.poc.disruptor.event.MarketEvent;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stands in for N independent exchange gateway connections, each pushing its
 * own ordered stream of ticks/trades/acks. Each session runs on its own
 * thread and calls {@link RingBuffer#next()} / {@link RingBuffer#publish(long)}
 * directly — safe to do concurrently from multiple threads only because the
 * pipeline is built with {@code ProducerType.MULTI} (see
 * {@code DisruptorPipeline}), which makes the Disruptor use a CAS-based
 * {@code MultiProducerSequencer} instead of the cheaper single-producer one.
 */
public final class ExchangeFeedSimulator {

    private static final EventType[] TYPES = EventType.values();

    private ExchangeFeedSimulator() {
    }

    /**
     * Runs all sessions to completion and returns the number of events that
     * actually landed on the ring buffer (i.e. excluding intentionally
     * dropped ones, including intentional duplicates — both are real
     * publishes as far as the ring buffer is concerned).
     */
    public static long run(RingBuffer<MarketEvent> ringBuffer, FeedConfig cfg) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(cfg.sessionCount());
        AtomicLong totalProduced = new AtomicLong();
        for (int s = 0; s < cfg.sessionCount(); s++) {
            final int sessionId = s;
            Thread t = new Thread(() -> {
                try {
                    long produced = runSession(ringBuffer, cfg, sessionId);
                    totalProduced.addAndGet(produced);
                } finally {
                    done.countDown();
                }
            }, "feed-session-" + s);
            t.start();
        }
        done.await();
        return totalProduced.get();
    }

    private static long runSession(RingBuffer<MarketEvent> ringBuffer, FeedConfig cfg, int sessionId) {
        Random rng = new Random(cfg.seed() + sessionId);
        List<String> symbols = cfg.symbols();
        long produced = 0;
        long lastPublishedSeq = -1;

        for (long exchangeSeq = 0; exchangeSeq < cfg.eventsPerSession(); exchangeSeq++) {
            if (cfg.dropRate() > 0 && rng.nextDouble() < cfg.dropRate()) {
                // Simulated packet loss: the exchange "sent" this sequence number,
                // but it never reaches the ring buffer at all. The gap shows up
                // downstream when IntegrityCheckHandler sees the next seq jump.
                continue;
            }

            String symbol = symbols.get((int) (Math.abs(exchangeSeq + sessionId) % symbols.size()));
            EventType type = TYPES[(int) (exchangeSeq % TYPES.length)];
            double price = 10.0 + (exchangeSeq % 500) * 0.05;
            long qty = 100 + (exchangeSeq % 20) * 100;
            char side = (exchangeSeq % 2 == 0) ? 'B' : 'S';

            publish(ringBuffer, exchangeSeq, sessionId, symbol, type, price, qty, side, cfg, rng);
            produced++;
            lastPublishedSeq = exchangeSeq;

            if (cfg.duplicateRate() > 0 && rng.nextDouble() < cfg.duplicateRate() && lastPublishedSeq >= 0) {
                // Simulated at-least-once redelivery: same exchangeSeq, sent twice.
                publish(ringBuffer, lastPublishedSeq, sessionId, symbol, type, price, qty, side, cfg, rng);
                produced++;
            }
        }
        return produced;
    }

    private static void publish(RingBuffer<MarketEvent> ringBuffer, long exchangeSeq, int sessionId,
                                 String symbol, EventType type, double price, long qty, char side,
                                 FeedConfig cfg, Random rng) {
        long seq = ringBuffer.next();
        try {
            MarketEvent event = ringBuffer.get(seq);
            event.set(exchangeSeq, sessionId, symbol, type, price, qty, side, System.nanoTime());
            if (cfg.corruptRate() > 0 && rng.nextDouble() < cfg.corruptRate()) {
                // Flip a field AFTER the checksum was computed in set() — this is
                // what the checksum recheck in IntegrityCheckHandler must catch.
                event.corruptQuantityForTest();
            }
        } finally {
            ringBuffer.publish(seq);
        }
    }
}
