package vn.com.poc.realtime.sse;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.com.poc.realtime.service.PriceEventBus;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/sse")
public class SseController {

    private final PriceEventBus bus;
    private final ConcurrentHashMap<Long, SseEmitter> clients = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong();

    public SseController(PriceEventBus bus) {
        this.bus = bus;
    }

    @GetMapping(path = "/prices", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        long id = nextId.incrementAndGet();
        SseEmitter emitter = new SseEmitter(0L);
        clients.put(id, emitter);

        Runnable unsub = bus.subscribe(price -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("price")
                        .data(price));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });

        Runnable cleanup = () -> {
            unsub.run();
            clients.remove(id);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());

        try {
            emitter.send(SseEmitter.event().name("hello").data("connected #" + id));
        } catch (IOException ignored) {
            // client already gone
        }
        return emitter;
    }

    @GetMapping("/stats")
    public String stats() {
        return "active sse clients = " + clients.size() + ", bus subscribers = " + bus.subscriberCount();
    }
}
