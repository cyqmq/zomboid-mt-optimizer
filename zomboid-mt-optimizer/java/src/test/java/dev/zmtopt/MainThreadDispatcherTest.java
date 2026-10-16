package dev.zmtopt;

import dev.zmtopt.core.MainThreadDispatcher;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MainThreadDispatcherTest {

    @Test
    void drainsPostedTasks() {
        MainThreadDispatcher dispatcher = new MainThreadDispatcher();
        AtomicInteger counter = new AtomicInteger();
        dispatcher.post(counter::incrementAndGet);
        dispatcher.post(counter::incrementAndGet);
        assertEquals(2, dispatcher.size());
        dispatcher.drain();
        assertEquals(2, counter.get());
        assertEquals(0, dispatcher.size());
    }

    @Test
    void callbackErrorDoesNotBreakDrain() {
        MainThreadDispatcher dispatcher = new MainThreadDispatcher();
        AtomicInteger counter = new AtomicInteger();
        dispatcher.post(() -> {
            throw new RuntimeException("boom");
        });
        dispatcher.post(counter::incrementAndGet);
        dispatcher.drain();
        assertEquals(1, counter.get(), "second task should still run after a failing one");
    }
}