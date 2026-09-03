package vn.com.poc.disruptor.pipeline;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;

/**
 * The wait strategy is the single biggest throughput/latency/CPU knob the
 * Disruptor exposes — it decides what a consumer thread does while waiting
 * for the next sequence to become available.
 *
 * <table>
 *   <caption>trade-offs</caption>
 *   <tr><td>{@code busy_spin}</td><td>Lowest latency, burns one full core per
 *       waiting consumer permanently. Only worth it if you can pin threads to
 *       dedicated cores and you truly need sub-microsecond latency.</td></tr>
 *   <tr><td>{@code yielding}</td><td>Spins calling {@code Thread.onSpinWait()}
 *       then {@code Thread.yield()}; very low latency, still CPU-hungry but
 *       plays a little nicer with the scheduler than busy-spin.</td></tr>
 *   <tr><td>{@code sleeping}</td><td>Spins briefly, then parks for
 *       nanoseconds at a time; a good default for throughput benchmarks that
 *       don't want to peg every core.</td></tr>
 *   <tr><td>{@code blocking}</td><td>A real lock + condition variable.
 *       Lowest CPU usage, highest and least predictable latency. Right choice
 *       when consumer threads vastly outnumber cores.</td></tr>
 * </table>
 */
public final class WaitStrategies {

    private WaitStrategies() {
    }

    public static WaitStrategy byName(String name) {
        return switch (name.toLowerCase()) {
            case "busy_spin" -> new BusySpinWaitStrategy();
            case "yielding" -> new YieldingWaitStrategy();
            case "sleeping" -> new SleepingWaitStrategy();
            case "blocking" -> new BlockingWaitStrategy();
            default -> throw new IllegalArgumentException("unknown wait strategy: " + name
                    + " (expected busy_spin|yielding|sleeping|blocking)");
        };
    }
}
