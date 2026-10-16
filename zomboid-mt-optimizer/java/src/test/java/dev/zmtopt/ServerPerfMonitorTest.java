package dev.zmtopt;

import dev.zmtopt.optimize.ServerPerfMonitor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerPerfMonitorTest {

    @Test
    void computesStats() {
        ServerPerfMonitor monitor = new ServerPerfMonitor(64);
        monitor.recordTick(10);
        monitor.recordTick(20);
        monitor.recordTick(30);
        assertEquals(20.0, monitor.getAverageTickMs(), 0.001);
        assertEquals(30.0, monitor.getMaxTickMs(), 0.001);
        assertEquals(20.0, monitor.getMedianTickMs(), 0.001);
    }

    @Test
    void handlesZeroReadings() {
        ServerPerfMonitor monitor = new ServerPerfMonitor(64);
        assertEquals(0.0, monitor.getAverageTickMs(), 0.001);
        assertEquals(0.0, monitor.getMaxTickMs(), 0.001);
    }
}