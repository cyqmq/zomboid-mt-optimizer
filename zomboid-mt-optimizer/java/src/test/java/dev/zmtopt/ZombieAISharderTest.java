package dev.zmtopt;

import dev.zmtopt.optimize.ZombieAISharder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ZombieAISharderTest {

    @Test
    void balancesLoadAcrossTicks() {
        ZombieAISharder sharder = new ZombieAISharder(4, 5);
        int[] perTick = new int[8];
        for (long tick = 0; tick < 8; tick++) {
            sharder.onTick(tick);
            int count = 0;
            for (int z = 0; z < 8; z++) {
                if (sharder.shouldTick(z, 100)) {
                    count++;
                }
            }
            perTick[(int) tick] = count;
        }
        for (int c : perTick) {
            assertEquals(2, c, "8 far zombies / 4 shards = 2 per tick");
        }
    }

    @Test
    void closeZombiesAlwaysTick() {
        ZombieAISharder sharder = new ZombieAISharder(4, 5);
        sharder.onTick(0);
        assertTrue(sharder.shouldTick(999, 1), "close zombie always ticks");
    }

    @Test
    void allFarZombiesUpdateWithinShardCycle() {
        ZombieAISharder sharder = new ZombieAISharder(4, 5);
        boolean[] ticked = new boolean[8];
        for (long tick = 0; tick < 4; tick++) {
            sharder.onTick(tick);
            for (int z = 0; z < 8; z++) {
                if (sharder.shouldTick(z, 100)) {
                    ticked[z] = true;
                }
            }
        }
        for (boolean b : ticked) {
            assertTrue(b, "every far zombie should update within a full shard cycle");
        }
    }
}