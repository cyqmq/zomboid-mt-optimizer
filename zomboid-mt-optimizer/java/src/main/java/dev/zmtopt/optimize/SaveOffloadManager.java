package dev.zmtopt.optimize;

import dev.zmtopt.core.MTThreadPool;
import dev.zmtopt.core.MainThreadDispatcher;
import dev.zmtopt.core.PriorityTask;

/**
 * Offloads the heavy parts of world saving (serialization, compression,
 * database writes) onto a background thread so the main simulation thread
 * does not stall during autosaves.
 *
 * <p>The heavy work runs on the worker pool; completion callbacks that touch
 * game objects are marshalled back to the main thread via
 * {@link MainThreadDispatcher}.
 */
public final class SaveOffloadManager {

    private final MTThreadPool pool;
    private final MainThreadDispatcher dispatcher;
    private long offloadCount;
    private long totalOffloadNanos;

    public SaveOffloadManager(MTThreadPool pool, MainThreadDispatcher dispatcher) {
        if (pool == null) {
            throw new NullPointerException("pool");
        }
        if (dispatcher == null) {
            throw new NullPointerException("dispatcher");
        }
        this.pool = pool;
        this.dispatcher = dispatcher;
    }

    /**
     * Runs {@code heavyWork} on a background thread.
     *
     * @param phaseName        label used in diagnostics
     * @param heavyWork        the serialization/database work (must not touch
     *                         game objects that are unsafe off-thread; copy the
     *                         needed data out on the main thread first)
     * @param onMainThreadDone optional callback executed on the main thread
     *                         after {@code heavyWork} completes
     */
    public void offloadSave(String phaseName, Runnable heavyWork, Runnable onMainThreadDone) {
        if (heavyWork == null) {
            throw new NullPointerException("heavyWork");
        }
        final long start = System.nanoTime();
        pool.submit(() -> {
            try {
                heavyWork.run();
            } finally {
                long elapsed = System.nanoTime() - start;
                synchronized (this) {
                    offloadCount++;
                    totalOffloadNanos += elapsed;
                }
                if (onMainThreadDone != null) {
                    dispatcher.post(onMainThreadDone);
                }
            }
        }, PriorityTask.PRIORITY_CRITICAL);
    }

    /** Total number of save phases that have been offloaded. */
    public synchronized long getOffloadCount() {
        return offloadCount;
    }

    /** Total wall time spent on background save work, in nanoseconds. */
    public synchronized long getTotalOffloadNanos() {
        return totalOffloadNanos;
    }

    public synchronized double getAverageOffloadMillis() {
        if (offloadCount == 0) {
            return 0.0;
        }
        return (totalOffloadNanos / 1_000_000.0) / offloadCount;
    }
}