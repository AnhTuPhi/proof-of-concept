package vn.com.poc.disruptor.event;

/** Kinds of message an exchange feed session emits. */
public enum EventType {
    TRADE,
    QUOTE,
    ORDER_ACK
}
