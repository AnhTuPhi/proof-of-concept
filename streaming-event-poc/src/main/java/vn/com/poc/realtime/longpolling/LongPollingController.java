package vn.com.poc.realtime.longpolling;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import vn.com.poc.realtime.model.StockPrice;
import vn.com.poc.realtime.service.PriceEventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/poll")
public class LongPollingController {

    private static final int RING_CAPACITY = 500;

    private final Queue<Entry> ring = new ConcurrentLinkedQueue<>();
    private final AtomicLong cursor = new AtomicLong();
    private final List<Waiter> waiters = new CopyOnWriteArrayList<>();

    public LongPollingController(PriceEventBus bus) {
        bus.subscribe(this::onPrice);
    }

    private void onPrice(StockPrice price) {
        long id = cursor.incrementAndGet();
        ring.add(new Entry(id, price));
        while (ring.size() > RING_CAPACITY) ring.poll();

        List<Waiter> toWake = new ArrayList<>(waiters);
        if (toWake.isEmpty()) return;
        waiters.removeAll(toWake);
        for (Waiter w : toWake) {
            PollResult snap = snapshotSince(w.since);
            if (!w.deferred.isSetOrExpired()) {
                w.deferred.setResult(snap);
            }
        }
    }

    private PollResult snapshotSince(long since) {
        List<StockPrice> data = new ArrayList<>();
        long maxCursor = since;
        for (Entry e : ring) {
            if (e.id > since) {
                data.add(e.price);
                if (e.id > maxCursor) maxCursor = e.id;
            }
        }
        return new PollResult(maxCursor, data);
    }

    @GetMapping("/prices")
    public DeferredResult<PollResult> poll(
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(defaultValue = "25000") long timeoutMs) {

        DeferredResult<PollResult> dr = new DeferredResult<>(timeoutMs, new PollResult(cursor, List.of()));

        PollResult snap = snapshotSince(cursor);
        if (!snap.data().isEmpty()) {
            dr.setResult(snap);
            return dr;
        }

        Waiter w = new Waiter(cursor, dr);
        waiters.add(w);

        // Double-check: an event might have landed between snapshot and waiters.add
        PollResult recheck = snapshotSince(cursor);
        if (!recheck.data().isEmpty() && waiters.remove(w) && !dr.isSetOrExpired()) {
            dr.setResult(recheck);
        }

        dr.onTimeout(() -> waiters.remove(w));
        dr.onCompletion(() -> waiters.remove(w));
        return dr;
    }

    private record Entry(long id, StockPrice price) {}

    private record Waiter(long since, DeferredResult<PollResult> deferred) {}

    public record PollResult(long cursor, List<StockPrice> data) {}
}
