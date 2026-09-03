package vn.com.poc.disruptor.bench;

import org.HdrHistogram.Histogram;

import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around HdrHistogram, recording end-to-end pipeline latency:
 * time from "producer stamped {@code ingestNanos} while publishing" to "the
 * last handler in the chain (OutboxHandler) observed the event".
 *
 * <p>Plain {@link Histogram}, not {@code ConcurrentHistogram} — safe only
 * because exactly one thread (the single OutboxHandler instance's consumer
 * thread) ever calls {@link #recordNanos(long)}.
 */
public final class LatencyRecorder {

    private final Histogram histogram;

    public LatencyRecorder() {
        // track up to 10 seconds of latency (would indicate a badly saturated pipeline),
        // 3 significant digits of precision
        this.histogram = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
    }

    public void recordNanos(long nanos) {
        if (nanos >= 0) {
            histogram.recordValue(nanos);
        }
    }

    public double p50Micros() {
        return histogram.getValueAtPercentile(50.0) / 1000.0;
    }

    public double p99Micros() {
        return histogram.getValueAtPercentile(99.0) / 1000.0;
    }

    public double p999Micros() {
        return histogram.getValueAtPercentile(99.9) / 1000.0;
    }

    public double maxMicros() {
        return histogram.getMaxValue() / 1000.0;
    }

    public long count() {
        return histogram.getTotalCount();
    }

    public String renderSummary() {
        return String.format(
                "latency (us)  p50=%.1f  p99=%.1f  p99.9=%.1f  max=%.1f  (n=%d)",
                p50Micros(), p99Micros(), p999Micros(), maxMicros(), count());
    }
}
