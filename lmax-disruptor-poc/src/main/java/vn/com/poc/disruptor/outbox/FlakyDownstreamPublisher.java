package vn.com.poc.disruptor.outbox;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stand-in for a real downstream (Kafka broker, clearing gateway, ...) that
 * is unreliable in exactly the ways real network services are: transient
 * failures at some steady-state rate. Exists so the retry/backoff/dead-letter
 * path in {@link vn.com.poc.disruptor.outbox.OutboxDispatcher} can be
 * exercised deterministically in tests and demonstrated under the benchmark's
 * chaos mode.
 */
public final class FlakyDownstreamPublisher implements DownstreamPublisher {

    private final double failureRate;
    private final AtomicLong callCount = new AtomicLong();
    private final AtomicLong publishedCount = new AtomicLong();

    public FlakyDownstreamPublisher(double failureRate) {
        this.failureRate = failureRate;
    }

    @Override
    public void publish(OutboxRecord record) throws PublishException {
        callCount.incrementAndGet();
        if (failureRate > 0 && ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw new PublishException("simulated transient failure publishing outbox id=" + record.id());
        }
        publishedCount.incrementAndGet();
    }

    public long callCount() {
        return callCount.get();
    }

    public long publishedCount() {
        return publishedCount.get();
    }
}
