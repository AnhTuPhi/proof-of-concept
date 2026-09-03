package vn.com.poc.disruptor.pipeline;

import com.lmax.disruptor.WaitStrategy;

import java.nio.file.Path;

/**
 * @param ringBufferSize     must be a power of two — the Disruptor uses a
 *                           bitmask (size-1) instead of a modulo to map a
 *                           sequence to a slot index, which only works for
 *                           power-of-two sizes
 * @param maxSessions        upper bound on concurrent exchange sessions, used
 *                           to size the gap-detector's lookup array
 * @param businessWorkerCount number of parallel symbol-sharded business-logic
 *                           handlers
 * @param waitStrategy       see {@link WaitStrategies}
 * @param journalPath        write-ahead log file for accepted events
 * @param quarantinePath     write-ahead log file for poisoned (checksum-failed) events
 */
public record PipelineConfig(
        int ringBufferSize,
        int maxSessions,
        int businessWorkerCount,
        WaitStrategy waitStrategy,
        Path journalPath,
        Path quarantinePath) {
}
