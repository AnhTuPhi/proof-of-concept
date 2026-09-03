package com.claude.kafka.streams.windowing;

import com.claude.kafka.common.topic.Topics;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Three windowing flavors in one topology:
 *
 * <ul>
 *   <li><strong>Tumbling (1 min):</strong> "page views per user per minute" —
 *       discrete, non-overlapping. The default for billing/usage metrics.</li>
 *
 *   <li><strong>Hopping (5 min window advancing every 1 min):</strong> rolling
 *       averages, "is this user about to be rate-limited?" Each event lands in
 *       multiple windows. <em>Watch your storage</em> — hopping multiplies
 *       state-store size by window/advance ratio.</li>
 *
 *   <li><strong>Session (30 sec inactivity gap):</strong> "user activity
 *       sessions" — windows grow with traffic and end when the user goes
 *       quiet. Right tool for funnels and engagement analytics.</li>
 * </ul>
 *
 * Grace periods matter: without {@code Duration.ofSeconds(10)} grace, a record
 * arriving 11 seconds after window close is dropped silently. In production
 * that drops are invisible unless you instrument the topology.
 */
@Configuration
public class ClickstreamTopology {

    @Autowired
    public void buildTopology(StreamsBuilder builder) {
        KStream<String, String> clicks = builder.stream(
                Topics.CLICKSTREAM,
                Consumed.with(Serdes.String(), Serdes.String()));

        // 1. Tumbling per-minute count
        KTable<Windowed<String>, Long> perMinute = clicks
                .groupByKey()
                .windowedBy(TimeWindows.ofSizeAndGrace(
                        Duration.ofMinutes(1), Duration.ofSeconds(10)))
                .count(Materialized.as("clicks-per-minute-store"));

        perMinute.toStream()
                .map((k, v) -> KeyValue.pair(
                        k.key() + "@" + k.window().startTime(),
                        String.valueOf(v)))
                .to(Topics.CLICKSTREAM_WINDOW,
                        Produced.with(Serdes.String(), Serdes.String()));

        // 2. Hopping 5-min window advancing every 1 min - rolling rate
        clicks.groupByKey()
                .windowedBy(TimeWindows.ofSizeAndGrace(
                                Duration.ofMinutes(5), Duration.ofSeconds(10))
                        .advanceBy(Duration.ofMinutes(1)))
                .count(Materialized.as("rolling-5min-store"));

        // 3. Session window with 30 sec inactivity gap - user engagement sessions
        clicks.groupByKey()
                .windowedBy(SessionWindows.ofInactivityGapWithNoGrace(Duration.ofSeconds(30)))
                .count(Materialized.as("session-store"));
    }
}
