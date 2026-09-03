package com.demo.patterns.cqrses;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccountAggregateTest {

    @Test
    void replays_state_from_event_history() {
        List<AccountEvent> history = new ArrayList<>();
        AccountAggregate agg = AccountAggregate.replay("acc_1", history);
        AccountEvent e1 = agg.open(100);
        history.add(e1); agg.apply(e1);
        AccountEvent e2 = agg.deposit(50);
        history.add(e2); agg.apply(e2);
        AccountEvent e3 = agg.withdraw(30);
        history.add(e3); agg.apply(e3);

        AccountAggregate replayed = AccountAggregate.replay("acc_1", history);
        assertEquals(120, replayed.balance());
        assertEquals(3, replayed.version());
    }

    @Test
    void withdrawal_rejected_when_insufficient_balance() {
        AccountAggregate agg = new AccountAggregate();
        var events = new ArrayList<AccountEvent>();
        AccountEvent open = AccountAggregate.replay("acc_1", events).open(10);
        agg.apply(open);
        events.add(open);
        AccountAggregate live = AccountAggregate.replay("acc_1", events);
        assertThrows(IllegalStateException.class, () -> live.withdraw(50));
    }

    @Test
    void cannot_deposit_to_unopened_account() {
        AccountAggregate fresh = AccountAggregate.replay("acc_1", List.of());
        assertThrows(IllegalStateException.class, () -> fresh.deposit(10));
    }
}
