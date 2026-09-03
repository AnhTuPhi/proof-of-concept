package vn.com.poc.realtime.service;

import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vn.com.poc.realtime.model.StockPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class StockPriceGenerator {

    private static final List<String> SYMBOLS = List.of("VND", "FPT", "HPG", "VIC", "MWG", "VHM", "MBB");

    private final Map<String, BigDecimal> lastPrice = new ConcurrentHashMap<>();
    private final PriceEventBus bus;

    public StockPriceGenerator(PriceEventBus bus) {
        this.bus = bus;
    }

    @PostConstruct
    public void seed() {
        lastPrice.put("VND", new BigDecimal("22.50"));
        lastPrice.put("FPT", new BigDecimal("125.00"));
        lastPrice.put("HPG", new BigDecimal("27.80"));
        lastPrice.put("VIC", new BigDecimal("45.20"));
        lastPrice.put("MWG", new BigDecimal("55.60"));
        lastPrice.put("VHM", new BigDecimal("42.10"));
        lastPrice.put("MBB", new BigDecimal("20.40"));
    }

    @Scheduled(fixedRate = 500)
    public void tick() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int count = 1 + rnd.nextInt(3);
        for (int i = 0; i < count; i++) {
            String symbol = SYMBOLS.get(rnd.nextInt(SYMBOLS.size()));
            BigDecimal prev = lastPrice.get(symbol);

            double deltaPct = (rnd.nextDouble() - 0.5) * 0.04;
            BigDecimal change = prev.multiply(BigDecimal.valueOf(deltaPct))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal next = prev.add(change).max(BigDecimal.ONE);
            lastPrice.put(symbol, next);

            BigDecimal changePct = change.divide(prev, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
            long volume = 1_000L + rnd.nextLong(50_000L);

            bus.publish(new StockPrice(symbol, next, change, changePct, volume, Instant.now()));
        }
    }
}
