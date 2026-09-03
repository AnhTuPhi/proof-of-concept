package com.poc.batchingest.batch.partitioner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Splits a numeric id range over a source table into equally-sized partitions. Each worker
 * step gets {@code minId} / {@code maxId} bounds in its execution context, which the paging
 * reader then uses as the {@code WHERE id BETWEEN ? AND ?} predicate.
 *
 * <p>Assumes the id column is monotonically increasing with low skew. For a heavily skewed
 * key space, partition by hash bucket instead.
 */
@Slf4j
public class IdRangePartitioner implements Partitioner {

    private final JdbcTemplate jdbc;
    private final String tableName;
    private final String idColumn;

    public IdRangePartitioner(JdbcTemplate jdbc, String tableName, String idColumn) {
        this.jdbc = jdbc;
        this.tableName = tableName;
        this.idColumn = idColumn;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        Long min = jdbc.queryForObject("SELECT MIN(" + idColumn + ") FROM " + tableName, Long.class);
        Long max = jdbc.queryForObject("SELECT MAX(" + idColumn + ") FROM " + tableName, Long.class);
        if (min == null || max == null) {
            log.warn("Range partitioner found empty source table {}", tableName);
            return Map.of();
        }

        long total = max - min + 1;
        long bucket = Math.max(1, total / gridSize);
        Map<String, ExecutionContext> partitions = new HashMap<>(gridSize);

        long start = min;
        int idx = 0;
        while (start <= max && idx < gridSize) {
            boolean last = (idx == gridSize - 1) || (start + bucket - 1 >= max);
            long end = last ? max : start + bucket - 1;

            ExecutionContext ctx = new ExecutionContext();
            ctx.putLong("minId", start);
            ctx.putLong("maxId", end);
            ctx.putString("partitionId", "partition-" + idx);
            partitions.put("partition-" + idx, ctx);

            start = end + 1;
            idx++;
        }

        log.info("IdRangePartitioner produced {} partition(s) over [{},{}] on {}",
                partitions.size(), min, max, tableName);
        return partitions;
    }
}
