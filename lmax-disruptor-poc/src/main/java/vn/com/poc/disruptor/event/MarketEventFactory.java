package vn.com.poc.disruptor.event;

import com.lmax.disruptor.EventFactory;

/** Pre-allocates every ring buffer slot up front, at {@code Disruptor.start()} time. */
public final class MarketEventFactory implements EventFactory<MarketEvent> {

    @Override
    public MarketEvent newInstance() {
        return new MarketEvent();
    }
}
