package com.vndirect.kstreams.producer;

import com.vndirect.kstreams.config.AppProperties;
import com.vndirect.kstreams.model.OrderEvent;
import com.vndirect.kstreams.model.PaymentEvent;
import com.vndirect.kstreams.model.Product;
import com.vndirect.kstreams.model.User;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Seeds the products/users compacted topics on startup, then emits a
 * steady drip of orders + matching payments so every topology has data
 * to chew on with no external producer needed.
 */
@Component
@ConditionalOnProperty(value = "app.demo.auto-generate", havingValue = "true", matchIfMissing = true)
public class DemoDataGenerator {

    private static final Logger log = LoggerFactory.getLogger(DemoDataGenerator.class);

    private static final List<Product> PRODUCTS = List.of(
            new Product("P-001", "VND-Equity-Premium", "EQUITY", new BigDecimal("150000")),
            new Product("P-002", "VND-Bond-Stable", "BOND", new BigDecimal("100000")),
            new Product("P-003", "VND-ETF-Growth", "ETF", new BigDecimal("80000")),
            new Product("P-004", "VND-Margin-Account", "MARGIN", new BigDecimal("250000")),
            new Product("P-005", "VND-Saving-Plus", "SAVING", new BigDecimal("50000")),
            new Product("P-006", "VND-Crypto-Lite", "CRYPTO", new BigDecimal("300000"))
    );

    private static final List<User> USERS = List.of(
            new User("U-1001", "Nguyen Van A", "GOLD", "VN"),
            new User("U-1002", "Tran Thi B", "SILVER", "VN"),
            new User("U-1003", "Le Van C", "PLATINUM", "VN"),
            new User("U-1004", "Pham Thi D", "BRONZE", "VN"),
            new User("U-1005", "Hoang Van E", "GOLD", "SG"),
            new User("U-1006", "Do Thi F", "SILVER", "JP")
    );

    private final EventPublisher publisher;
    private final AppProperties props;

    public DemoDataGenerator(EventPublisher publisher, AppProperties props) {
        this.publisher = publisher;
        this.props = props;
    }

    @PostConstruct
    public void seedReferenceData() {
        log.info("Seeding {} products and {} users to compacted topics",
                PRODUCTS.size(), USERS.size());
        PRODUCTS.forEach(publisher::publishProduct);
        USERS.forEach(publisher::publishUser);
    }

    @Scheduled(fixedDelayString = "${app.demo.interval-ms:1500}", initialDelay = 5_000)
    public void emitOrderAndPayment() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Product product = PRODUCTS.get(rnd.nextInt(PRODUCTS.size()));
        User user = USERS.get(rnd.nextInt(USERS.size()));

        String orderId = "O-" + UUID.randomUUID().toString().substring(0, 8);
        int qty = rnd.nextInt(1, 6);
        BigDecimal priceJitter = product.basePrice()
                .multiply(BigDecimal.valueOf(0.95 + rnd.nextDouble() * 0.10))
                .setScale(0, RoundingMode.HALF_UP);
        Instant orderedAt = Instant.now();

        OrderEvent order = new OrderEvent(orderId, user.userId(), product.productId(),
                qty, priceJitter, orderedAt);
        publisher.publishOrder(order);

        // 80% of orders get a payment shortly after; 5% fail
        if (rnd.nextDouble() < 0.80) {
            PaymentEvent.PaymentMethod method = PaymentEvent.PaymentMethod.values()[
                    rnd.nextInt(PaymentEvent.PaymentMethod.values().length)];
            PaymentEvent.PaymentStatus status = rnd.nextDouble() < 0.05
                    ? PaymentEvent.PaymentStatus.DECLINED
                    : PaymentEvent.PaymentStatus.APPROVED;

            PaymentEvent payment = new PaymentEvent(
                    "PAY-" + UUID.randomUUID().toString().substring(0, 8),
                    orderId,
                    order.totalAmount(),
                    method,
                    status,
                    orderedAt.plusMillis(rnd.nextLong(100, 3000))
            );
            publisher.publishPayment(payment);
        }
    }
}
