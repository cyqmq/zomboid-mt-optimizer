package dev.zmtopt;

import dev.zmtopt.agent.ZMTOptAgent;
import dev.zmtopt.bridge.FJLPlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerZombieThrottleTest {

    @Test
    void neverSkipsWhenDisabled() {
        ZMTOptAgent.setServerZombieThrottleEnabled(false);
        ZMTOptAgent.setServerZombieThrottleThresholdMs(50.0);
        for (int i = 0; i < 5; i++) {
            assertFalse(ZMTOptAgent.shouldSkipServerZombieUpdate(),
                    "disabled throttle must never skip");
        }
    }

    @Test
    void skipsWhenOverloaded() {
        FJLPlugin.init(); // ensure perf monitor exists
        FJLPlugin.getPerfMonitor().reset();
        ZMTOptAgent.setServerZombieThrottleEnabled(true);
        ZMTOptAgent.setServerZombieThrottleThresholdMs(50.0);
        // Fill the ring buffer with slow ticks.
        for (int i = 0; i < 20; i++) {
            FJLPlugin.getPerfMonitor().recordTick(100.0);
        }
        boolean anySkipped = false;
        for (int i = 0; i < 4; i++) {
            if (ZMTOptAgent.shouldSkipServerZombieUpdate()) {
                anySkipped = true;
                break;
            }
        }
        assertTrue(anySkipped, "overloaded server should skip some native updates");
        ZMTOptAgent.setServerZombieThrottleEnabled(false);
    }

    @Test
    void doesNotSkipWhenHealthy() {
        FJLPlugin.init();
        FJLPlugin.getPerfMonitor().reset();
        ZMTOptAgent.setServerZombieThrottleEnabled(true);
        ZMTOptAgent.setServerZombieThrottleThresholdMs(50.0);
        // Fill the ring buffer with fast ticks.
        for (int i = 0; i < 200; i++) {
            FJLPlugin.getPerfMonitor().recordTick(10.0);
        }
        assertFalse(ZMTOptAgent.shouldSkipServerZombieUpdate(),
                "healthy server should not skip native zombie updates");
        ZMTOptAgent.setServerZombieThrottleEnabled(false);
    }
}