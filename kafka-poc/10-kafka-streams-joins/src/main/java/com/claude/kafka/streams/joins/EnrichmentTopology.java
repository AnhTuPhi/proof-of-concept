package com.claude.kafka.streams.joins;

import com.claude.kafka.common.topic.Topics;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.kstream.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Three production-flavored joins:
 *
 * <ol>
 *   <li><strong>KStream-KTable (user profile enrichment).</strong> Each click
 *       is enriched with the latest user profile. The KTable is co-partitioned
 *       with the stream (same key = userId), which is what makes the join cheap.
 *       If your data isn't co-partitioned, you MUST {@code repartition()} or
 *       Streams will silently produce wrong results.</li>
 *
 *   <li><strong>KStream-GlobalKTable (low-cardinality lookup).</strong> For
 *       small reference tables (country codes, tenant settings) replicated
 *       to every task. No co-partitioning needed. Use this when the lookup
 *       table fits in RAM on every node.</li>
 *
 *   <li><strong>KStream-KStream temporal join.</strong> "Click followed by
 *       purchase within 10 minutes" — the window decides what counts as
 *       "matching". Inner join here; left/outer join when you want unmatched
 *       events through too.</li>
 * </ol>
 */
@Configuration
public class EnrichmentTopology {

    @Autowired
    public void buildTopology(StreamsBuilder builder) {

        KStream<String, String> clicks = builder.stream(
                Topics.CLICKSTREAM,
                Consumed.with(Serdes.String(), Serdes.String()));

        // KTable: the latest profile per user, materialized as a local store
        KTable<String, String> userProfiles = builder.table(
                Topics.USERS_TABLE,
                Materialized.as("user-profiles-store"));

        // 1. KStream-KTable join: enrich each click with the user's profile
        KStream<String, String> enriched = clicks.leftJoin(
                userProfiles,
                (click, profile) -> profile == null
                        ? click.replace("}", ",\"profile\":null}")
                        : click.replace("}", ",\"profile\":" + profile + "}"));

        enriched.to(Topics.ENRICHED_CLICKS,
                Produced.with(Serdes.String(), Serdes.String()));

        // 2. KStream-KStream join: click followed by purchase within 10 min
        KStream<String, String> purchases = builder.stream(
                Topics.ORDERS_PLACED,
                Consumed.with(Serdes.String(), Serdes.String()));

        KStream<String, String> clickPurchase = clicks.join(
                purchases,
                (click, purchase) -> "{\"click\":" + click + ",\"purchase\":" + purchase + "}",
                JoinWindows.ofTimeDifferenceWithNoGrace(Duration.ofMinutes(10)),
                StreamJoined.with(Serdes.String(), Serdes.String(), Serdes.String()));

        clickPurchase.to("clickstream.attributed.v1",
                Produced.with(Serdes.String(), Serdes.String()));
    }
}
