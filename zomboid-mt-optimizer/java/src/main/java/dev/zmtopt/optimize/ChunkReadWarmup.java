package dev.zmtopt.optimize;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background chunk-file warmup.
 *
 * <p>Strategy: before the game's single-threaded {@code WorldStreamer} asks
 * for a chunk, pre-read that chunk's file on a background I/O pool and
 * discard the bytes. This warms the OS page cache, so the game's later read
 * hits RAM instead of disk - reducing chunk-streaming hitches without touching
 * any game logic. This is the safe, additive half of C2ME-style chunk I/O
 * parallelization.
 *
 * <p>All game access is via reflection and wrapped in try/catch:
 * <ul>
 *   <li>{@code zombie.ChunkMapFilenames.instance.getFilename(wx, wy)} - file path</li>
 *   <li>{@code zombie.characters.IsoPlayer.getPlayers()} + {@code getX()}/{@code getY()}</li>
 * </ul>
 * If any reflection fails (renamed classes, client without the API, etc.) the
 * warmup simply stops without affecting the game.
 */
public final class ChunkReadWarmup {

    private final ChunkIOPool ioPool;
    private final int radiusChunks;
    private final int chunkSizeTiles;
    private final long intervalTicks;
    private final Object lock = new Object();
    private final Set<Long> scheduledChunks = new HashSet<>();
    private final AtomicLong warmedFiles = new AtomicLong();
    private final AtomicLong skippedFiles = new AtomicLong();

    private long lastWarmupTick;

    // Reflection handles (resolved lazily).
    private boolean reflectionAttempted = false;
    private boolean reflectionReady = false;
    private Object chunkMapFilenamesInstance;
    private Method getFilenameMethod;
    private Method getPlayersMethod;
    private Method getXMethod;
    private Method getYMethod;

    /**
     * @param ioPool       the chunk I/O pool
     * @param radiusChunks number of chunks to warm in each direction around players
     * @param chunkSizeTiles size of one map chunk in tiles (used to convert
     *                       player tile coords to chunk coords)
     * @param intervalTicks how many ticks between warmup passes
     */
    public ChunkReadWarmup(ChunkIOPool ioPool, int radiusChunks, int chunkSizeTiles, long intervalTicks) {
        if (ioPool == null) {
            throw new NullPointerException("ioPool");
        }
        this.ioPool = ioPool;
        this.radiusChunks = Math.max(1, radiusChunks);
        this.chunkSizeTiles = Math.max(1, chunkSizeTiles);
        this.intervalTicks = Math.max(1, intervalTicks);
    }

    /** Called once per tick from the game thread. */
    public void onTick(long currentTick) {
        if (currentTick - lastWarmupTick < intervalTicks) {
            return;
        }
        lastWarmupTick = currentTick;
        ensureReflection();
        if (!reflectionReady) {
            return;
        }
        try {
            Object playersObj = getPlayersMethod.invoke(null);
            if (playersObj instanceof List<?> players) {
                for (Object player : players) {
                    if (player == null) {
                        continue;
                    }
                    float x = ((Number) getXMethod.invoke(player)).floatValue();
                    float y = ((Number) getYMethod.invoke(player)).floatValue();
                    warmAround(x, y);
                }
            }
        } catch (Throwable t) {
            // Game API changed; disable warmup silently.
            reflectionReady = false;
        }
    }

    private void warmAround(float tileX, float tileY) {
        int cx = Math.floorDiv((int) tileX, chunkSizeTiles);
        int cy = Math.floorDiv((int) tileY, chunkSizeTiles);
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dy = -radiusChunks; dy <= radiusChunks; dy++) {
                final int wx = cx + dx;
                final int wy = cy + dy;
                long key = (((long) wx) << 32) ^ (wy & 0xffffffffL);
                synchronized (lock) {
                    if (!scheduledChunks.add(key)) {
                        continue;
                    }
                }
                ioPool.submit("chunk-warmup", () -> readIntoPageCache(wx, wy));
            }
        }
    }

    private void readIntoPageCache(int wx, int wy) {
        try {
            Object fileObj = getFilenameMethod.invoke(chunkMapFilenamesInstance, wx, wy);
            File file = null;
            if (fileObj instanceof File f) {
                file = f;
            } else if (fileObj instanceof String s) {
                file = new File(s);
            }
            if (file != null && file.isFile()) {
                try (InputStream in = Files.newInputStream(file.toPath())) {
                    byte[] buf = new byte[8192];
                    while (in.read(buf) != -1) {
                        // Discard - the point is to warm the OS page cache.
                    }
                }
                warmedFiles.incrementAndGet();
            } else {
                skippedFiles.incrementAndGet();
            }
        } catch (Throwable ignored) {
            // File may not exist yet (chunk not generated) - perfectly normal.
        }
    }

    private void ensureReflection() {
        if (reflectionAttempted) {
            return;
        }
        reflectionAttempted = true;
        try {
            Class<?> chunkMapFilenamesClass = Class.forName("zombie.ChunkMapFilenames");
            chunkMapFilenamesInstance = chunkMapFilenamesClass.getField("instance").get(null);
            getFilenameMethod = chunkMapFilenamesClass.getMethod("getFilename", int.class, int.class);

            Class<?> isoPlayerClass = Class.forName("zombie.characters.IsoPlayer");
            getPlayersMethod = isoPlayerClass.getMethod("getPlayers");
            getXMethod = isoPlayerClass.getMethod("getX");
            getYMethod = isoPlayerClass.getMethod("getY");

            reflectionReady = true;
        } catch (Throwable t) {
            System.err.println("[ZMTOpt] ChunkReadWarmup reflection not available: " + t);
        }
    }

    public long getWarmedFiles() {
        return warmedFiles.get();
    }

    public long getSkippedFiles() {
        return skippedFiles.get();
    }

    public int getScheduledChunkCount() {
        synchronized (lock) {
            return scheduledChunks.size();
        }
    }
}