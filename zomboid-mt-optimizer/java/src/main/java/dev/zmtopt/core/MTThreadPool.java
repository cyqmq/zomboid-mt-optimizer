package dev.zmtopt.core;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A named, priority-aware worker pool used to move heavy work off the
 * game's main simulation thread.
 *
 * <p>The pool uses a {@link PriorityBlockingQueue} fed with
 * {@link PriorityTask}s and a {@code CallerRunsPolicy} so that, when the
 * pool is saturated, the excess work runs on the calling thread instead of
 * being silently dropped.
 */
public final class MTThreadPool {

    private final String name;
    private final int coreThreads;
    private final int maxThreads;
    private final ThreadPoolExecutor executor;
    private final AtomicLong sequence = new AtomicLong();

    public MTThreadPool(String name, int coreThreads, int maxThreads) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name");
        }
        this.name = name;
        this.coreThreads = Math.max(1, coreThreads);
        this.maxThreads = Math.max(this.coreThreads, maxThreads);
        this.executor = new ThreadPoolExecutor(
                this.coreThreads,
                this.maxThreads,
                60L,
                TimeUnit.SECONDS,
                new PriorityBlockingQueue<Runnable>(),
                new NamedThreadFactory(name),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public void submit(Runnable task, int priority) {
        executor.execute(new PriorityTask(task, priority, sequence.getAndIncrement()));
    }

    public void submit(Runnable task) {
        submit(task, PriorityTask.PRIORITY_NORMAL);
    }

    /** Number of tasks queued but not yet started. */
    public int getPendingCount() {
        return executor.getQueue().size();
    }

    /** Number of tasks currently executing. */
    public int getActiveCount() {
        return executor.getActiveCount();
    }

    public String getName() {
        return name;
    }

    public int getCoreThreads() {
        return coreThreads;
    }

    public int getMaxThreads() {
        return maxThreads;
    }

    /** Gracefully stops the pool, waiting up to 5 seconds for in-flight work. */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger();

        NamedThreadFactory(String name) {
            this.prefix = name + "-worker";
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        }
    }
}