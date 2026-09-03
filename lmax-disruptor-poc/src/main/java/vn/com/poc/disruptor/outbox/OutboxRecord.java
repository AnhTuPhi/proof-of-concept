package vn.com.poc.disruptor.outbox;

/** A row claimed off the outbox table, ready to be handed to a {@link DownstreamPublisher}. */
public record OutboxRecord(long id, long exchangeSeq, int sessionId, String symbol, String payload, int attempts) {
}
