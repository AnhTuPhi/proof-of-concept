package com.demo.patterns.cqrses;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Command side of CQRS. Loads the event stream for an aggregate, applies a
 * command, writes the new event(s) atomically. A unique (aggregateId,version)
 * constraint provides optimistic concurrency on the event log itself.
 */
@Service
public class AccountCommandService {

    private final AccountEventRepository events;
    private final ApplicationEventPublisher publisher;

    public AccountCommandService(AccountEventRepository events,
                                 ApplicationEventPublisher publisher) {
        this.events = events;
        this.publisher = publisher;
    }

    @Transactional
    public String openAccount(long initialDeposit) {
        String id = "acc_" + UUID.randomUUID();
        AccountAggregate agg = AccountAggregate.replay(id, List.of());
        AccountEvent event = agg.open(initialDeposit);
        return persist(event);
    }

    @Transactional
    public void deposit(String id, long amount) {
        AccountAggregate agg = load(id);
        persist(agg.deposit(amount));
    }

    @Transactional
    public void withdraw(String id, long amount) {
        AccountAggregate agg = load(id);
        persist(agg.withdraw(amount));
    }

    public AccountAggregate load(String id) {
        List<AccountEvent> history = events.findByAggregateIdOrderByVersionAsc(id);
        if (history.isEmpty()) {
            throw new IllegalArgumentException("No account: " + id);
        }
        return AccountAggregate.replay(id, history);
    }

    public List<AccountEvent> history(String id) {
        return events.findByAggregateIdOrderByVersionAsc(id);
    }

    private String persist(AccountEvent event) {
        try {
            AccountEvent saved = events.save(event);
            // Publish AFTER persist so projection sees the same row the store has.
            publisher.publishEvent(new EventPersisted(saved));
            return event.getAggregateId();
        } catch (DataIntegrityViolationException dive) {
            throw new ConcurrencyConflictException(
                    "Concurrent write on " + event.getAggregateId() + " v" + event.getVersion());
        }
    }

    public record EventPersisted(AccountEvent event) {}

    public static class ConcurrencyConflictException extends RuntimeException {
        public ConcurrencyConflictException(String msg) { super(msg); }
    }
}
