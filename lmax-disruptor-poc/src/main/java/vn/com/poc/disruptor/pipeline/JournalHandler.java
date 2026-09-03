package vn.com.poc.disruptor.pipeline;

import com.lmax.disruptor.EventHandler;
import vn.com.poc.disruptor.event.MarketEvent;
import vn.com.poc.disruptor.metrics.PipelineMetrics;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Second stage: durable write-ahead log, written <b>before</b> business logic
 * touches the event. This is the "crash after this point is replayable"
 * guarantee — if the JVM dies mid-batch, every event that made it into the
 * journal (and was {@code force}d to disk) can be replayed from the file on
 * restart, even though its in-memory business-state effect and outbox row
 * never happened.
 *
 * <p>Single instance, single writer thread (the Disruptor consumer thread for
 * this stage) — so the {@link FileChannel} needs no external synchronization;
 * nothing else ever touches it.
 *
 * <p>Durability vs. throughput lever: we batch lines in memory and only call
 * {@link FileChannel#force(boolean)} (fsync) once per Disruptor batch
 * ({@code endOfBatch}), not once per event. Under load the Disruptor
 * naturally coalesces a burst of publishes into one batch, so this handler
 * gets bigger batches — and proportionally fewer fsyncs — exactly when
 * throughput is highest. At most one batch's worth of journaled-but-not-yet-
 * fsynced events can be lost in a crash between two batches.
 */
public final class JournalHandler implements EventHandler<MarketEvent> {

    private final FileChannel channel;
    private final FileChannel quarantineChannel;
    private final PipelineMetrics metrics;
    private final StringBuilder pending = new StringBuilder(1 << 16);

    public JournalHandler(Path journalPath, Path quarantinePath, PipelineMetrics metrics) throws IOException {
        this.channel = FileChannel.open(journalPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        this.quarantineChannel = FileChannel.open(quarantinePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        this.metrics = metrics;
    }

    @Override
    public void onEvent(MarketEvent event, long sequence, boolean endOfBatch) throws IOException {
        if (event.isPoisoned()) {
            writeImmediately(quarantineChannel, event.toWireLine());
            metrics.incQuarantined();
        } else {
            pending.append(event.toWireLine()).append('\n');
            metrics.incJournaled();
        }

        if (endOfBatch && !pending.isEmpty()) {
            channel.write(ByteBuffer.wrap(pending.toString().getBytes(StandardCharsets.UTF_8)));
            channel.force(false);
            pending.setLength(0);
        }
    }

    private void writeImmediately(FileChannel target, String line) throws IOException {
        target.write(ByteBuffer.wrap((line + '\n').getBytes(StandardCharsets.UTF_8)));
    }

    public void close() throws IOException {
        if (!pending.isEmpty()) {
            channel.write(ByteBuffer.wrap(pending.toString().getBytes(StandardCharsets.UTF_8)));
            pending.setLength(0);
        }
        channel.force(true);
        channel.close();
        quarantineChannel.force(true);
        quarantineChannel.close();
    }
}
