package com.vndirect.kstreams.api;

import com.vndirect.kstreams.model.OrderEvent;
import com.vndirect.kstreams.model.PaymentEvent;
import com.vndirect.kstreams.model.Product;
import com.vndirect.kstreams.model.User;
import com.vndirect.kstreams.producer.EventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final EventPublisher publisher;

    public DemoController(EventPublisher publisher) {
        this.publisher = publisher;
    }

    @PostMapping("/orders")
    public ResponseEntity<Map<String, String>> publishOrder(@RequestBody OrderEvent body) {
        OrderEvent order = body.orderId() == null
                ? new OrderEvent("O-" + UUID.randomUUID().toString().substring(0, 8),
                        body.userId(), body.productId(), body.quantity(),
                        body.unitPrice(), body.orderedAt() == null ? Instant.now() : body.orderedAt())
                : body;
        publisher.publishOrder(order);
        return ResponseEntity.accepted().body(Map.of(
                "orderId", order.orderId(),
                "totalAmount", order.totalAmount().toPlainString()
        ));
    }

    @PostMapping("/payments")
    public ResponseEntity<Map<String, String>> publishPayment(@RequestBody PaymentEvent body) {
        PaymentEvent payment = body.paymentId() == null
                ? new PaymentEvent("PAY-" + UUID.randomUUID().toString().substring(0, 8),
                        body.orderId(), body.amount(), body.method(), body.status(),
                        body.paidAt() == null ? Instant.now() : body.paidAt())
                : body;
        publisher.publishPayment(payment);
        return ResponseEntity.accepted().body(Map.of("paymentId", payment.paymentId()));
    }

    @PostMapping("/products")
    public ResponseEntity<Product> publishProduct(@RequestBody Product product) {
        publisher.publishProduct(product);
        return ResponseEntity.accepted().body(product);
    }

    @PostMapping("/users")
    public ResponseEntity<User> publishUser(@RequestBody User user) {
        publisher.publishUser(user);
        return ResponseEntity.accepted().body(user);
    }

    @PostMapping("/quick-order")
    public ResponseEntity<Map<String, Object>> quickOrder() {
        String orderId = "O-" + UUID.randomUUID().toString().substring(0, 8);
        OrderEvent order = new OrderEvent(orderId, "U-1001", "P-001", 2,
                new BigDecimal("150000"), Instant.now());
        publisher.publishOrder(order);

        PaymentEvent payment = new PaymentEvent(
                "PAY-" + UUID.randomUUID().toString().substring(0, 8),
                orderId, order.totalAmount(),
                PaymentEvent.PaymentMethod.CARD,
                PaymentEvent.PaymentStatus.APPROVED,
                Instant.now().plusMillis(500));
        publisher.publishPayment(payment);

        return ResponseEntity.accepted().body(Map.of(
                "order", order,
                "payment", payment
        ));
    }
}
