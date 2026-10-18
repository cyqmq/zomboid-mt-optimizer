package dev.zmtopt.optimize;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight, allocation-friendly performance telemetry. Records tick
 * durations in a fixed ring buffer and exposes average/max stats so server
 * admins can verify the effect of the optimization layer.
 *
 * <p>All timings are in milliseconds (double). The buffer size is fixed at
 * construction time to avoid growing memory usage on long-running servers.
 */
public final class ServerPerfMonitor {

    private final long[] tickTimesMs;
    private final int capacity;
    private final AtomicInteger cursor = new AtomicInteger();
    private final AtomicLong maxTickMs = new AtomicLong();
    private volatile double lastTickMs;

    public ServerPerfMonitor(int bufferSize) {
        this.capacity = Math.max(16, bufferSize);
        this.tickTimesMs = new long[capacity];
    }

    public void recordTick(double durationMs) {
        int index = cursor.getAndUpdate(i -> (i + 1) % capacity);
        tickTimesMs[index] = (long) durationMs;
        lastTickMs = durationMs;
        maxTickMs.accumulateAndGet((long) durationMs, Math::max);
    }

    public double getLastTickMs() {
        return lastTickMs;
    }

    public double getMaxTickMs() {
        return maxTickMs.get();
    }

    public double getAverageTickMs() {
        long sum = 0;
        int count = 0;
        for (long v : tickTimesMs) {
            if (v != 0L) {
                sum += v;
                count++;
            }
        }
        return count == 0 ? 0.0 : (double) sum / count;
    }

    public double getMedianTickMs() {
        long[] copy = Arrays.copyOf(tickTimesMs, tickTimesMs.length);
        int count = 0;
        for (long v : copy) {
            if (v != 0L) {
                count++;
            }
        }
        if (count == 0) {
            return 0.0;
        }
        Arrays.sort(copy, 0, count);
        int mid = count / 2;
        if (count % 2 == 1) {
            return copy[mid];
        }
        return (copy[mid - 1] + copy[mid]) / 2.0;
    }

    /** Resets all recorded timings (used by tests and server restart flows). */
    public void reset() {
        Arrays.fill(tickTimesMs, 0L);
        cursor.set(0);
        maxTickMs.set(0);
        lastTickMs = 0.0;
    }
}