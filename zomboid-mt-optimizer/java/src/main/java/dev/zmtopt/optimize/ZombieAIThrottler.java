package dev.zmtopt.optimize;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies distance-based LOD to zombie AI updates, mirroring the technique
 * used by commercial optimization patches: zombies close to any player are
 * simulated every tick, while distant zombies have their AI (pathfinding,
 * sensory updates) evaluated less frequently.
 *
 * <p>This is a scheduler, not a game-code patch. The game hook layer consults
 * {@link #shouldTick(int, int, long)} before dispatching an AI update for a
 * given zombie. Distant zombies therefore skip redundant update ticks without
 * any observable change when the player approaches.
 */
public final class ZombieAIThrottler {

    private final Map<Integer, Long> lastTickByZombie = new ConcurrentHashMap<>();
    private final int closeRadiusTiles;
    private final int farIntervalTicks;

    /**
     * @param closeRadiusTiles distance (in tiles) within which zombies always
     *                         simulate every tick
     * @param farIntervalTicks update interval (in ticks) for zombies outside
     *                         the close radius; must be &gt;= 1
     */
    public ZombieAIThrottler(int closeRadiusTiles, int farIntervalTicks) {
        this.closeRadiusTiles = Math.max(1, closeRadiusTiles);
        this.farIntervalTicks = Math.max(1, farIntervalTicks);
    }

    /**
     * Decides whether the AI update for zombie {@code zombieId} should run this
     * tick.
     *
     * @param zombieId       stable id of the zombie
     * @param distanceTiles  distance to the nearest player, in tiles
     * @param currentTick    current server tick
     * @return true if the AI update should run
     */
    public boolean shouldTick(int zombieId, int distanceTiles, long currentTick) {
        if (distanceTiles <= closeRadiusTiles) {
            // Close zombies are always simulated; remember their tick so that
            // moving out of range does not cause an immediate double update.
            lastTickByZombie.put(zombieId, currentTick);
            return true;
        }
        Long last = lastTickByZombie.get(zombieId);
        if (last == null || currentTick - last >= farIntervalTicks) {
            lastTickByZombie.put(zombieId, currentTick);
            return true;
        }
        return false;
    }

    /** Removes a zombie that despawned or died. */
    public void remove(int zombieId) {
        lastTickByZombie.remove(zombieId);
    }

    public void clear() {
        lastTickByZombie.clear();
    }

    /** Number of zombies currently tracked. */
    public int size() {
        return lastTickByZombie.size();
    }
}