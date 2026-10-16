package dev.zmtopt.core;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Marshals tasks from background threads back to the game's main simulation
 * thread. Background workers enqueue results with {@link #post(Runnable)};
 * the game tick hook calls {@link #drain()} once per tick so all callbacks
 * execute on the main thread where game objects are safe to touch.
 */
public final class MainThreadDispatcher {

    private final Queue<Runnable> pending = new ArrayDeque<>();
    private final Object lock = new Object();

    public void post(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        synchronized (lock) {
            pending.add(task);
        }
    }

    /** Runs all pending tasks on the calling thread (must be the game thread). */
    public void drain() {
        while (true) {
            Runnable task;
            synchronized (lock) {
                task = pending.poll();
            }
            if (task == null) {
                break;
            }
            try {
                task.run();
            } catch (Throwable t) {
                // Never let a callback take down the simulation thread.
                System.err.println("[ZMTOpt] main-thread task error: " + t.getMessage());
                t.printStackTrace();
            }
        }
    }

    public int size() {
        synchronized (lock) {
            return pending.size();
        }
    }
}