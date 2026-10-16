package dev.zmtopt;

import dev.zmtopt.core.MainThreadDispatcher;
import dev.zmtopt.core.MTThreadPool;
import dev.zmtopt.optimize.SaveOffloadManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SaveOffloadManagerTest {

    @Test
    void offloadsWorkAndMarshalsCallback() throws Exception {
        MTThreadPool pool = new MTThreadPool("test-save", 1, 1);
        MainThreadDispatcher dispatcher = new MainThreadDispatcher();
        SaveOffloadManager manager = new SaveOffloadManager(pool, dispatcher);

        AtomicBoolean heavyDone = new AtomicBoolean();
        AtomicBoolean callbackRan = new AtomicBoolean();
        CountDownLatch latch = new CountDownLatch(1);

        manager.offloadSave("test", () -> {
            heavyDone.set(true);
        }, () -> {
            callbackRan.set(true);
            latch.countDown();
        });

        // Drain the main-thread dispatcher until the callback arrives.
        for (int i = 0; i < 100 && !callbackRan.get(); i++) {
            dispatcher.drain();
            Thread.sleep(10);
        }
        dispatcher.drain();

        assertTrue(heavyDone.get(), "heavy work should run");
        assertTrue(callbackRan.get(), "callback should be marshalled to main thread");
        assertEquals(1, manager.getOffloadCount());
        pool.shutdown();
    }
}