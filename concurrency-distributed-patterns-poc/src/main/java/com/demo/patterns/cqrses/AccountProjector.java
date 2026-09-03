package com.demo.patterns.cqrses;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens for committed event-store writes and projects them into the read
 * model. Runs in a new transaction so a slow projection can't roll back the
 * command-side commit.
 *
 * In a real system this would consume from Kafka/Debezium and survive
 * crashes via a checkpoint. Here we keep the demo synchronous-after-commit
 * for clarity.
 */
@Component
public class AccountProjector {

    private static final Logger log = LoggerFactory.getLogger(AccountProjector.class);

    private final AccountBalanceViewRepository views;

    public AccountProjector(AccountBalanceViewRepository views) {
        this.views = views;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(AccountCommandService.EventPersisted ep) {
        AccountEvent e = ep.event();
        AccountBalanceView view = views.findById(e.getAggregateId())
                .orElseGet(() -> new AccountBalanceView(e.getAggregateId(), 0, 0));

        long delta = switch (e.getType()) {
            case "AccountOpened", "MoneyDeposited" -> amount(e);
            case "MoneyWithdrawn" -> -amount(e);
            default -> 0L;
        };

        long newBalance = view.getBalance() + delta;
        view.update(newBalance, e.getVersion());
        views.save(view);
        log.info("Projected {} v{} → balance={}", e.getType(), e.getVersion(), newBalance);
    }

    /** Rebuild all views from scratch — handy after schema changes. */
    @EventListener
    public void onRebuildRequest(RebuildAllViews req) {
        log.warn("Rebuild requested — not implemented for the demo; see README.");
    }

    private static long amount(AccountEvent e) {
        String payload = e.getPayload();
        String field = payload.contains("\"initial\"") ? "initial" : "amount";
        int start = payload.indexOf("\"" + field + "\":") + field.length() + 3;
        int end = payload.indexOf('}', start);
        return Long.parseLong(payload.substring(start, end).trim());
    }

    public record RebuildAllViews() {}
}
