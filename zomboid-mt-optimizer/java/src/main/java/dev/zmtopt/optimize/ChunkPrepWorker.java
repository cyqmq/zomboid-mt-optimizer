package dev.zmtopt.optimize;

import dev.zmtopt.core.MTThreadPool;
import dev.zmtopt.core.PriorityTask;

/**
 * Precomputes chunk-related data on background threads so that chunk loading
 * and streaming cause fewer hitches on the main thread.
 *
 * <p>The actual chunk objects live inside the closed-source game jar. To avoid
 * a hard compile-time dependency, this class works against the
 * {@link ChunkDataProvider} bridge interface, which the game hook layer
 * implements using reflection over {@code zombie.iso.*} classes.
 */
public final class ChunkPrepWorker {

    private final MTThreadPool pool;
    private final int maxQueue;
    private long requestsServed;
    private long requestsDropped;

    public ChunkPrepWorker(MTThreadPool pool, int maxQueue) {
        if (pool == null) {
            throw new NullPointerException("pool");
        }
        this.pool = pool;
        this.maxQueue = Math.max(1, maxQueue);
    }

    /**
     * Requests asynchronous preparation of a chunk.
     *
     * @param provider  bridge that knows how to prepare one chunk object
     * @param chunkRef  the game chunk object (passed through untyped)
     */
    public void requestPrep(ChunkDataProvider provider, Object chunkRef) {
        if (provider == null || chunkRef == null) {
            return;
        }
        // Backpressure: if the queue is full, skip prefetch. The chunk will
        // simply be prepared synchronously later, which is the vanilla path.
        if (pool.getPendingCount() >= maxQueue) {
            synchronized (this) {
                requestsDropped++;
            }
            return;
        }
        pool.submit(() -> {
            try {
                provider.prepare(chunkRef);
            } catch (Throwable t) {
                // Never let one bad chunk take down a worker.
                System.err.println("[ZMTOpt] chunk prep error: " + t.getMessage());
            }
        }, PriorityTask.PRIORITY_LOW);
        synchronized (this) {
            requestsServed++;
        }
    }

    public synchronized long getRequestsServed() {
        return requestsServed;
    }

    public synchronized long getRequestsDropped() {
        return requestsDropped;
    }

    /** Bridge implemented by the game hook layer (reflection). */
    @FunctionalInterface
    public interface ChunkDataProvider {
        void prepare(Object chunkRef);
    }
}