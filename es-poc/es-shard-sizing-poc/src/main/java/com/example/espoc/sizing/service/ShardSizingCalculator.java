package com.example.espoc.sizing.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates (daily ingest, retention, target shard size) into recommendations.
 * Mirrors what the conventional wisdom says — explicitly, so it can be questioned.
 */
@Service
public class ShardSizingCalculator {

    public Map<String, Object> calculate(double dailyGb, int retentionDays, double targetShardGb, int nodeCount, int heapGb) {
        double totalGb = dailyGb * retentionDays;
        int rolloverShards = Math.max(1, (int) Math.ceil(dailyGb / targetShardGb));   // shards per rollover index
        int indexes        = retentionDays;                                            // daily indexes
        int totalShards    = rolloverShards * indexes;                                 // primaries only
        double perShardActual = dailyGb / rolloverShards;

        int maxShardsPerNode = 20 * Math.max(1, heapGb);
        double shardsPerNode = totalShards / (double) Math.max(1, nodeCount);
        boolean shardsOk = shardsPerNode <= maxShardsPerNode;

        String rolloverAdvice;
        if (dailyGb < targetShardGb) {
            rolloverAdvice = "Daily volume (" + dailyGb + " GB) is below target shard size. " +
                    "Use ILM rollover with max_size=" + targetShardGb + "gb max_age=7d so multiple days share one shard.";
        } else {
            rolloverAdvice = "Use ILM rollover with max_size=" + targetShardGb + "gb max_age=1d.";
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("inputs", Map.of("dailyGb", dailyGb, "retentionDays", retentionDays,
                "targetShardGb", targetShardGb, "nodes", nodeCount, "heapGb", heapGb));
        r.put("totalDataGb", totalGb);
        r.put("primariesPerRollover", rolloverShards);
        r.put("rolloverIndexes", indexes);
        r.put("totalPrimaries", totalShards);
        r.put("perShardActualGb", round(perShardActual));
        r.put("shardsPerNode", round(shardsPerNode));
        r.put("maxShardsPerNode", maxShardsPerNode);
        r.put("withinShardBudget", shardsOk);
        r.put("rolloverAdvice", rolloverAdvice);
        if (!shardsOk) {
            r.put("warning", "Per-node shard count (" + round(shardsPerNode) + ") exceeds the " +
                    "20 × heap_GB rule (" + maxShardsPerNode + "). Add nodes, lower retention, or raise target shard size.");
        }
        return r;
    }

    private static double round(double d) { return Math.round(d * 100.0) / 100.0; }
}
