package dev.zmtopt.core;

/**
 * A runnable wrapper carrying a scheduling priority and a monotonically
 * increasing sequence number. Lower priority values run first; tasks with
 * the same priority run in FIFO order.
 *
 * <p>Priority levels:
 * <ul>
 *   <li>{@link #PRIORITY_CRITICAL} - save data, must not be dropped</li>
 *   <li>{@link #PRIORITY_HIGH} - player-facing synchronous work</li>
 *   <li>{@link #PRIORITY_NORMAL} - regular background work</li>
 *   <li>{@link #PRIORITY_LOW} - prefetch / precompute, safe to delay</li>
 * </ul>
 */
public final class PriorityTask implements Runnable, Comparable<PriorityTask> {

    public static final int PRIORITY_CRITICAL = 0;
    public static final int PRIORITY_HIGH = 1;
    public static final int PRIORITY_NORMAL = 2;
    public static final int PRIORITY_LOW = 3;

    private final Runnable delegate;
    private final int priority;
    private final long sequence;

    public PriorityTask(Runnable delegate, int priority, long sequence) {
        if (delegate == null) {
            throw new NullPointerException("delegate");
        }
        this.delegate = delegate;
        this.priority = priority;
        this.sequence = sequence;
    }

    @Override
    public void run() {
        delegate.run();
    }

    public int getPriority() {
        return priority;
    }

    @Override
    public int compareTo(PriorityTask other) {
        int byPriority = Integer.compare(this.priority, other.priority);
        if (byPriority != 0) {
            return byPriority;
        }
        return Long.compare(this.sequence, other.sequence);
    }

    @Override
    public String toString() {
        return "PriorityTask{priority=" + priority + ", sequence=" + sequence + '}';
    }
}