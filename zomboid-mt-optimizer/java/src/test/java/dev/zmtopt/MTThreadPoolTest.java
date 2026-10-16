package dev.zmtopt;

import dev.zmtopt.core.MTThreadPool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MTThreadPoolTest {

    @Test
    void executesSubmittedTasks() throws Exception {
        MTThreadPool pool = new MTThreadPool("test", 2, 2);
        CountDownLatch latch = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            pool.submit(latch::countDown);
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS), "all tasks should complete");
        pool.shutdown();
    }

    @Test
    void higherPriorityRunsFirst() throws Exception {
        MTThreadPool pool = new MTThreadPool("test-prio", 1, 1);
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch gate = new CountDownLatch(1);

        // Gatekeeper occupies the single worker.
        pool.submit(() -> {
            try {
                gate.await();
            } catch (InterruptedException ignored) {
            }
            order.add("gatekeeper");
        });

        Thread.sleep(100); // let the gatekeeper start

        // These two wait in the priority queue.
        pool.submit(() -> order.add("low"), dev.zmtopt.core.PriorityTask.PRIORITY_LOW);
        pool.submit(() -> order.add("high"), dev.zmtopt.core.PriorityTask.PRIORITY_HIGH);

        gate.countDown();
        Thread.sleep(300);

        assertEquals("gatekeeper", order.get(0));
        assertEquals("high", order.get(1), "high priority should leave the queue before low");
        assertEquals("low", order.get(2));
        pool.shutdown();
    }

    @Test
    void pendingCountReflectsQueue() throws Exception {
        MTThreadPool pool = new MTThreadPool("test-pending", 1, 1);
        CountDownLatch gate = new CountDownLatch(1);
        pool.submit(() -> {
            try {
                gate.await();
            } catch (InterruptedException ignored) {
            }
        });
        pool.submit(() -> { /* queued */ });
        assertTrue(pool.getPendingCount() >= 1, "second task should be queued");
        gate.countDown();
        pool.shutdown();
    }
}