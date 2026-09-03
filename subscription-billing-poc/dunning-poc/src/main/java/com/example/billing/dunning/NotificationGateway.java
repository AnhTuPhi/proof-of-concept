package com.example.billing.dunning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fake email/SMS gateway. Captures sent messages in-memory for inspection by tests.
 */
@Component
public class NotificationGateway {

    private static final Logger log = LoggerFactory.getLogger(NotificationGateway.class);

    private final List<SentNotification> sent = new ArrayList<>();

    public synchronized void send(Long subscriptionId, String template, String to) {
        SentNotification n = new SentNotification(subscriptionId, template, to);
        sent.add(n);
        log.info("Notification sent: subscription={} template={} to={}", subscriptionId, template, to);
    }

    public synchronized List<SentNotification> sentFor(Long subscriptionId) {
        return sent.stream().filter(s -> s.subscriptionId().equals(subscriptionId)).toList();
    }

    public synchronized List<SentNotification> all() {
        return Collections.unmodifiableList(new ArrayList<>(sent));
    }

    public synchronized void reset() {
        sent.clear();
    }

    public record SentNotification(Long subscriptionId, String template, String to) {
    }
}
