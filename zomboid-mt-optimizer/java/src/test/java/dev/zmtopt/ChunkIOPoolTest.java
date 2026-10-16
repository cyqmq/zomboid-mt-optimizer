package dev.zmtopt;

import dev.zmtopt.optimize.ChunkIOPool;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ChunkIOPoolTest {

    @Test
    void executesSubmittedIoTask() throws Exception {
        ChunkIOPool pool = new ChunkIOPool(1, 16);
        CountDownLatch latch = new CountDownLatch(1);
        pool.submit("test", latch::countDown);
        assertTrue(latch.await(2, TimeUnit.SECONDS), "I/O task should complete");
        pool.shutdown();
    }

    @Test
    void appliesBackpressure() {
        ChunkIOPool pool = new ChunkIOPool(1, 4);
        CountDownLatch gate = new CountDownLatch(1);
        pool.submit("blocking", () -> {
            try {
                gate.await();
            } catch (InterruptedException ignored) {
            }
        });
        for (int i = 0; i < 10; i++) {
            pool.submit("filler", () -> { /* no-op */ });
        }
        // Pending count should not exceed the cap (some submissions are skipped).
        assertTrue(pool.getPendingCount() <= 4, "pending queue should respect maxPending");
        gate.countDown();
        pool.shutdown();
    }
}