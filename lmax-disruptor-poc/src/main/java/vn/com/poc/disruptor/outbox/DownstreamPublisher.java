package vn.com.poc.disruptor.outbox;

/** The thing the outbox is trying to reliably deliver to (Kafka, a clearing gateway, a client-confirmation service, ...). */
public interface DownstreamPublisher {

    void publish(OutboxRecord record) throws PublishException;

    class PublishException extends Exception {
        public PublishException(String message) {
            super(message);
        }
    }
}
