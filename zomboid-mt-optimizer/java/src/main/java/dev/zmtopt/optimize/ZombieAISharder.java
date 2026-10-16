package dev.zmtopt.optimize;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frame-time distribution for zombie AI updates.
 *
 * <p>Instead of updating every distant zombie's AI on every tick (which causes
 * frame spikes), the distant zombie population is divided into N shards.
 * Each tick only one shard's distant zombies receive AI updates, so the
 * per-tick AI load is smoothed across the tick budget. This is the same idea
 * as "Tempo"-style frame-time distribution.
 *
 * <p>Scheduling decisions stay on the game thread (no game objects are
 * touched off-thread), so this is safe to combine with the other
 * optimization modules. Close zombies are always simulated; distant zombies
 * are assigned to a shard by their stable id and only update when their
 * shard is active.
 */
public final class ZombieAISharder {

    private final int shardCount;
    private final int closeRadiusTiles;
    private final Map<Integer, Integer> zombieShard = new ConcurrentHashMap<>();
    private long activeShard;

    /**
     * @param shardCount       number of shards (e.g. 4 = each distant zombie
     *                         updates every 4th tick)
     * @param closeRadiusTiles distance (in tiles) within which zombies always
     *                         simulate every tick
     */
    public ZombieAISharder(int shardCount, int closeRadiusTiles) {
        this.shardCount = Math.max(1, shardCount);
        this.closeRadiusTiles = Math.max(1, closeRadiusTiles);
    }

    /** Advances the active shard; call once per game tick. */
    public void onTick(long currentTick) {
        activeShard = Math.floorMod(currentTick, shardCount);
    }

    /**
     * Decides whether the AI update for zombie {@code zombieId} should run
     * this tick.
     *
     * @param zombieId       stable id of the zombie
     * @param distanceTiles  distance to the nearest player, in tiles
     * @return true if the AI update should run this tick
     */
    public boolean shouldTick(int zombieId, int distanceTiles) {
        if (distanceTiles <= closeRadiusTiles) {
            return true; // close zombies always simulate
        }
        int shard = zombieShard.computeIfAbsent(zombieId, id -> Math.floorMod(id, shardCount));
        return shard == activeShard;
    }

    /** Removes a zombie that despawned or died. */
    public void remove(int zombieId) {
        zombieShard.remove(zombieId);
    }

    public void clear() {
        zombieShard.clear();
    }

    /** Number of distant zombies currently tracked. */
    public int size() {
        return zombieShard.size();
    }

    public int getShardCount() {
        return shardCount;
    }
}