package dev.zmtopt.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Schedules recurring work at fixed tick intervals. Each scheduled task has
 * a phase offset so independent tasks do not all fire on the same tick,
 * spreading the per-tick load and avoiding frame spikes.
 */
public final class TickScheduler {

    private final Map<Integer, List<ScheduledTask>> tasksByInterval = new ConcurrentHashMap<>();
    private final MainThreadDispatcher dispatcher;

    public TickScheduler(MainThreadDispatcher dispatcher) {
        if (dispatcher == null) {
            throw new NullPointerException("dispatcher");
        }
        this.dispatcher = dispatcher;
    }

    /**
     * Schedules {@code task} to run every {@code intervalTicks} ticks, starting
     * at tick {@code phaseOffset}.
     *
     * @param intervalTicks interval in ticks, must be &gt;= 1
     * @param phaseOffset   tick offset used to desynchronize tasks (0..interval-1)
     * @param task          work to execute on the main thread
     */
    public void schedule(int intervalTicks, int phaseOffset, Runnable task) {
        int interval = Math.max(1, intervalTicks);
        int phase = ((phaseOffset % interval) + interval) % interval;
        List<ScheduledTask> list = tasksByInterval.computeIfAbsent(interval, k -> new CopyOnWriteArrayList<>());
        list.add(new ScheduledTask(task, phase));
    }

    /** Called once per game tick (from the Java bridge). */
    public void onTick(long currentTick) {
        for (Map.Entry<Integer, List<ScheduledTask>> entry : tasksByInterval.entrySet()) {
            int interval = entry.getKey();
            if (currentTick % interval != 0) {
                continue;
            }
            for (ScheduledTask task : entry.getValue()) {
                if (currentTick % interval == task.phase) {
                    dispatcher.post(task.task);
                }
            }
        }
    }

    public int getTaskCount() {
        int count = 0;
        for (List<ScheduledTask> list : tasksByInterval.values()) {
            count += list.size();
        }
        return count;
    }

    private static final class ScheduledTask {
        final Runnable task;
        final long phase;

        ScheduledTask(Runnable task, long phase) {
            this.task = task;
            this.phase = phase;
        }
    }
}