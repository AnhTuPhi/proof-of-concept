package vn.com.dgo.poc.hikari;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class LoadRunner {

    private static final Logger log = LoggerFactory.getLogger(LoadRunner.class);

    private final WorkService work;

    public LoadRunner(WorkService work) {
        this.work = work;
    }

    public LoadReport run(int concurrency, int totalRequests, int workMs) throws InterruptedException {
        ExecutorService exec = Executors.newFixedThreadPool(concurrency);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(totalRequests));
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(totalRequests);

        long t0 = System.nanoTime();
        for (int i = 0; i < totalRequests; i++) {
            exec.submit(() -> {
                long s = System.nanoTime();
                try {
                    work.doWork(workMs);
                } catch (Exception e) {
                    failures.incrementAndGet();
                    log.warn("Request failed: {}", e.getMessage());
                } finally {
                    latencies.add((System.nanoTime() - s) / 1_000_000);
                    done.countDown();
                }
            });
        }
        done.await(5, TimeUnit.MINUTES);
        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        exec.shutdownNow();

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);

        return new LoadReport(
                concurrency,
                totalRequests,
                workMs,
                totalMs,
                failures.get(),
                percentile(sorted, 50),
                percentile(sorted, 95),
                percentile(sorted, 99),
                sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1),
                totalMs == 0 ? 0 : 1000.0 * totalRequests / totalMs);
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1);
        idx = Math.max(0, idx);
        return sorted.get(idx);
    }

    public record LoadReport(
            int concurrency,
            int totalRequests,
            int workMs,
            long totalMs,
            int failures,
            long p50Ms,
            long p95Ms,
            long p99Ms,
            long maxMs,
            double throughputRps) {
    }
}
