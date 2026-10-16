package dev.zmtopt;

import dev.zmtopt.optimize.ZombieAIThrottler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ZombieAIThrottlerTest {

    @Test
    void closeZombiesAlwaysTick() {
        ZombieAIThrottler throttle = new ZombieAIThrottler(15, 10);
        assertTrue(throttle.shouldTick(1, 5, 1), "close zombie should always tick");
    }

    @Test
    void farZombiesAreThrottled() {
        ZombieAIThrottler throttle = new ZombieAIThrottler(15, 10);
        assertTrue(throttle.shouldTick(2, 100, 1), "first far update allowed");
        assertFalse(throttle.shouldTick(2, 100, 2), "second far update within interval blocked");
        assertTrue(throttle.shouldTick(2, 100, 11), "far update after interval allowed");
    }

    @Test
    void removeCleansTracking() {
        ZombieAIThrottler throttle = new ZombieAIThrottler(15, 10);
        throttle.shouldTick(3, 100, 1);
        throttle.remove(3);
        assertTrue(throttle.shouldTick(3, 100, 2), "after remove, next update allowed");
    }
}