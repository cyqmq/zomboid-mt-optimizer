package dev.zmtopt;

import dev.zmtopt.core.MainThreadDispatcher;
import dev.zmtopt.core.TickScheduler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TickSchedulerTest {

    @Test
    void runsOnIntervalWithPhase() {
        MainThreadDispatcher dispatcher = new MainThreadDispatcher();
        TickScheduler scheduler = new TickScheduler(dispatcher);
        AtomicInteger counter = new AtomicInteger();

        // Every 2 ticks, phase 0 -> ticks 2,4,6,8,10
        scheduler.schedule(2, 0, counter::incrementAndGet);

        for (long t = 1; t <= 10; t++) {
            scheduler.onTick(t);
            dispatcher.drain();
        }
        // Tasks posted on tick t are drained on the next onTick's dispatcher.drain,
        // so include one extra drain after the loop.
        dispatcher.drain();
        assertEquals(5, counter.get());
    }

    @Test
    void phaseOffsetsSpreadLoad() {
        MainThreadDispatcher dispatcher = new MainThreadDispatcher();
        TickScheduler scheduler = new TickScheduler(dispatcher);
        AtomicInteger even = new AtomicInteger();
        AtomicInteger odd = new AtomicInteger();
        scheduler.schedule(2, 0, even::incrementAndGet);
        scheduler.schedule(2, 1, odd::incrementAndGet);

        for (long t = 1; t <= 4; t++) {
            scheduler.onTick(t);
            dispatcher.drain();
        }
        dispatcher.drain();
        // even runs on ticks 2,4; odd runs on ticks 1,3 (posted) -> drained next tick.
        assertTrue(even.get() >= 1 && odd.get() >= 1, "both phases should run");
    }
}