package dev.zmtopt.optimize;

import dev.zmtopt.core.MTThreadPool;
import dev.zmtopt.core.PriorityTask;

/**
 * Dedicated bounded thread pool for chunk file I/O.
 *
 * <p>Modelled after C2ME's approach of parallelizing chunk I/O: disk reads
 * and writes are moved off the game's main/simulation thread onto a small
 * pool of I/O worker threads. The pool is bounded so that under heavy load
 * it applies backpressure instead of piling up unbounded work.
 */
public final class ChunkIOPool {

    private final MTThreadPool pool;
    private final int maxPending;

    /**
     * @param threads   number of I/O worker threads (1-4 is sensible)
     * @param maxPending maximum queued tasks before new tasks are skipped
     */
    public ChunkIOPool(int threads, int maxPending) {
        int workerCount = Math.max(1, Math.min(4, threads));
        this.pool = new MTThreadPool("ZMTOpt-ChunkIO", workerCount, workerCount);
        this.maxPending = Math.max(1, maxPending);
    }

    /**
     * Submits an I/O task. If the pending queue is full, the task is skipped
     * (the game will do the I/O synchronously, which is the vanilla path).
     *
     * @param label   diagnostic label
     * @param ioTask  the disk I/O work (must not touch game objects)
     */
    public void submit(String label, Runnable ioTask) {
        if (ioTask == null) {
            return;
        }
        if (pool.getPendingCount() >= maxPending) {
            return;
        }
        pool.submit(ioTask, PriorityTask.PRIORITY_LOW);
    }

    public int getPendingCount() {
        return pool.getPendingCount();
    }

    public int getActiveCount() {
        return pool.getActiveCount();
    }

    public void shutdown() {
        pool.shutdown();
    }
}