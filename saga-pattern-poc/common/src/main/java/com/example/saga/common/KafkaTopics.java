package com.example.saga.common;

/**
 * Single source of truth for Kafka topic names.
 * All saga events flow through {@link #SAGA_EVENTS} keyed by sagaId so that
 * each saga's event stream is totally ordered within one partition.
 * The dead-letter topic is where the listener container parks records that
 * exhausted the retry policy.
 */
public final class KafkaTopics {

    public static final String SAGA_EVENTS = "saga.events";
    public static final String SAGA_EVENTS_DLT = "saga.events.DLT";

    private KafkaTopics() {
    }
}
