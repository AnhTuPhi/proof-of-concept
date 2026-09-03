package vn.com.poc.realtime.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.com.poc.realtime.model.StockPrice;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class PriceEventBus {

    private static final Logger log = LoggerFactory.getLogger(PriceEventBus.class);

    private final List<Consumer<StockPrice>> subscribers = new CopyOnWriteArrayList<>();

    public Runnable subscribe(Consumer<StockPrice> listener) {
        subscribers.add(listener);
        return () -> subscribers.remove(listener);
    }

    public void publish(StockPrice price) {
        for (Consumer<StockPrice> s : subscribers) {
            try {
                s.accept(price);
            } catch (Exception e) {
                log.warn("subscriber threw: {}", e.toString());
            }
        }
    }

    public int subscriberCount() {
        return subscribers.size();
    }
}
