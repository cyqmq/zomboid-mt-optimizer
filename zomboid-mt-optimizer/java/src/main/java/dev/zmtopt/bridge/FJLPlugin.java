package dev.zmtopt.bridge;

import dev.zmtopt.agent.ZMTOptAgent;
import dev.zmtopt.core.MTThreadPool;
import dev.zmtopt.core.MainThreadDispatcher;
import dev.zmtopt.core.TickScheduler;
import dev.zmtopt.optimize.ChunkIOPool;
import dev.zmtopt.optimize.ChunkReadCache;
import dev.zmtopt.optimize.ChunkReadWarmup;
import dev.zmtopt.optimize.ChunkPrepWorker;
import dev.zmtopt.optimize.SaveOffloadManager;
import dev.zmtopt.optimize.ServerPerfMonitor;
import dev.zmtopt.optimize.ZombieAIThrottler;
import dev.zmtopt.optimize.ZombieAISharder;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Entry point of the Zomboid MT Optimizer Java layer.
 *
 * <p>This class is loaded by a Java injection framework (Faded Java Loader or
 * ZombieBuddy). It has a public static {@link #init()} method that the loader
 * or the Lua bridge invokes once the server starts, and a public static
 * {@link #onTick(long)} method that the server-tick hook calls every tick.
 *
 * <p>The class intentionally has no compile-time dependency on the game jar:
 * all engine access happens through {@link GameHooks} (reflection). The threading
 * core is plain JDK and fully testable outside the game.
 */
public final class FJLPlugin {

    private static volatile boolean initialized = false;
    private static MTThreadPool workerPool;
    private static MainThreadDispatcher dispatcher;
    private static TickScheduler scheduler;
    private static SaveOffloadManager saveOffload;
    private static ChunkPrepWorker chunkPrep;
    private static ZombieAIThrottler zombieThrottler;
    private static ServerPerfMonitor perfMonitor;
    private static ChunkIOPool chunkIOPool;
    private static ChunkReadWarmup chunkReadWarmup;
    private static ChunkReadCache chunkReadCache;
    private static ZombieAISharder zombieAISharder;

    // Periodic stats logging.
    private static volatile boolean statsEnabled = true;
    private static volatile long statsIntervalMs = 60_000L;
    private static volatile long lastStatsLogTime = 0L;

    private FJLPlugin() {
    }

    /** Initializes the optimization layer. Idempotent; safe to call twice. */
    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        GameHooks.init();
        GameHooks.log("[ZMTOpt] initializing Java layer...");

        int cpuCores = Runtime.getRuntime().availableProcessors();
        int workerCount = Math.max(1, cpuCores - 1);

        workerPool = new MTThreadPool("ZMTOpt", Math.min(2, workerCount), workerCount);
        dispatcher = new MainThreadDispatcher();
        scheduler = new TickScheduler(dispatcher);
        saveOffload = new SaveOffloadManager(workerPool, dispatcher);
        chunkPrep = new ChunkPrepWorker(workerPool, 64);
        zombieThrottler = new ZombieAIThrottler(15, 10);
        perfMonitor = new ServerPerfMonitor(200);

        initChunkIO();
        initZombieAISharder();

        statsEnabled = Boolean.parseBoolean(System.getProperty("zmtopt.stats.enable", "true"));
        statsIntervalMs = Long.getLong("zmtopt.stats.intervalMs", 60_000L);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (chunkIOPool != null) {
                chunkIOPool.shutdown();
            }
            workerPool.shutdown();
        }, "ZMTOpt-Shutdown"));

        GameHooks.log("[ZMTOpt] initialized with " + workerCount + " worker threads.");
    }

    /**
     * Creates the C2ME-style chunk I/O layer. Configuration comes from JVM
     * system properties (the Java layer cannot read the Lua config directly):
     * <ul>
     *   <li>{@code zmtopt.chunkIO.enable} - "true" (default) or "false"</li>
     *   <li>{@code zmtopt.chunkIO.threads} - I/O threads, default 2</li>
     *   <li>{@code zmtopt.chunkIO.maxPending} - queue cap, default 128</li>
     *   <li>{@code zmtopt.chunkIO.radius} - warmup radius in chunks, default 2</li>
     *   <li>{@code zmtopt.chunkIO.chunkSize} - map chunk size in tiles, default 300</li>
     *   <li>{@code zmtopt.chunkIO.intervalTicks} - warmup pass interval, default 40</li>
     * </ul>
     */
    private static void initChunkIO() {
        if (!Boolean.parseBoolean(System.getProperty("zmtopt.chunkIO.enable", "true"))) {
            GameHooks.log("[ZMTOpt] chunk I/O optimizer disabled by system property.");
            return;
        }
        int threads = Integer.getInteger("zmtopt.chunkIO.threads", 2);
        int maxPending = Integer.getInteger("zmtopt.chunkIO.maxPending", 128);
        int radius = Integer.getInteger("zmtopt.chunkIO.radius", 2);
        int chunkSize = Integer.getInteger("zmtopt.chunkIO.chunkSize", 300);
        long interval = Long.getLong("zmtopt.chunkIO.intervalTicks", 40L);

        chunkIOPool = new ChunkIOPool(threads, maxPending);
        chunkReadWarmup = new ChunkReadWarmup(chunkIOPool, radius, chunkSize, interval);
        chunkReadCache = new ChunkReadCache(chunkIOPool, 256);
        GameHooks.log("[ZMTOpt] chunk I/O optimizer ready (threads=" + threads
                + ", radius=" + radius + ", chunkSize=" + chunkSize + ").");
    }

    /**
     * Creates the frame-time distribution sharder for distant zombie AI.
     * Configuration (JVM system properties):
     * <ul>
     *   <li>{@code zmtopt.zombieAI.enable} - "true" (default) or "false"</li>
     *   <li>{@code zmtopt.zombieAI.shards} - shard count, default 4</li>
     *   <li>{@code zmtopt.zombieAI.closeRadius} - close radius in tiles, default 15</li>
     * </ul>
     */
    private static void initZombieAISharder() {
        if (!Boolean.parseBoolean(System.getProperty("zmtopt.zombieAI.enable", "true"))) {
            GameHooks.log("[ZMTOpt] zombie AI sharder disabled by system property.");
            return;
        }
        int shards = Integer.getInteger("zmtopt.zombieAI.shards", 4);
        int closeRadius = Integer.getInteger("zmtopt.zombieAI.closeRadius", 15);
        zombieAISharder = new ZombieAISharder(shards, closeRadius);
        GameHooks.log("[ZMTOpt] zombie AI sharder ready (shards=" + shards
                + ", closeRadius=" + closeRadius + ").");
    }

    /**
     * Called once per server tick (must run on the game thread).
     *
     * @param currentTick server tick counter
     */
    public static void onTick(long currentTick) {
        if (!initialized) {
            return;
        }
        long tickStart = System.nanoTime();
        dispatcher.drain();
        scheduler.onTick(currentTick);
        if (zombieAISharder != null) {
            zombieAISharder.onTick(currentTick);
        }
        if (chunkReadWarmup != null) {
            chunkReadWarmup.onTick(currentTick);
        }
        perfMonitor.recordTick((System.nanoTime() - tickStart) / 1_000_000.0);
        maybeLogStats(currentTick);
    }

    /** Periodically prints a one-line summary of all optimizer counters. */
    private static void maybeLogStats(long now) {
        if (!statsEnabled || now - lastStatsLogTime < statsIntervalMs) {
            return;
        }
        lastStatsLogTime = now;
        StringBuilder sb = new StringBuilder("[ZMTOpt-stats]");
        if (saveOffload != null) {
            sb.append(" saveOffloads=").append(saveOffload.getOffloadCount());
        }
        if (chunkReadCache != null) {
            sb.append(" chunkCacheHits=").append(chunkReadCache.getHits())
              .append(" misses=").append(chunkReadCache.getMisses());
        }
        if (chunkReadWarmup != null) {
            sb.append(" warmedFiles=").append(chunkReadWarmup.getWarmedFiles());
        }
        if (zombieAISharder != null) {
            sb.append(" shardedZombies=").append(zombieAISharder.size());
        }
        sb.append(" serverZombieTicks=").append(ZMTOptAgent.getServerZombieTickCount());
        if (perfMonitor != null) {
            sb.append(" avgTickMs=").append(String.format(Locale.ROOT, "%.1f", perfMonitor.getAverageTickMs()));
        }
        GameHooks.log(sb.toString());
    }

    /**
     * Called by the javaagent when game classes start loading. Retries the
     * LuaManager binding (which cannot succeed during premain because the
     * game classes are not loaded yet).
     */
    public static void onGameClassesLoaded() {
        if (!initialized) {
            return;
        }
        GameHooks.init();
        if (GameHooks.isBound()) {
            GameHooks.log("[ZMTOpt] game classes detected; hooks rebound.");
        }
    }

    // ------------------------------------------------------------------
    // Accessors for the Lua bridge and diagnostics.
    // ------------------------------------------------------------------

    public static boolean isInitialized() {
        return initialized;
    }

    public static MTThreadPool getWorkerPool() {
        return workerPool;
    }

    public static MainThreadDispatcher getDispatcher() {
        return dispatcher;
    }

    public static TickScheduler getScheduler() {
        return scheduler;
    }

    public static SaveOffloadManager getSaveOffload() {
        return saveOffload;
    }

    public static ChunkPrepWorker getChunkPrep() {
        return chunkPrep;
    }

    public static ZombieAIThrottler getZombieThrottler() {
        return zombieThrottler;
    }

    public static ServerPerfMonitor getPerfMonitor() {
        return perfMonitor;
    }

    public static ChunkIOPool getChunkIOPool() {
        return chunkIOPool;
    }

    public static ChunkReadWarmup getChunkReadWarmup() {
        return chunkReadWarmup;
    }

    public static ChunkReadCache getChunkReadCache() {
        return chunkReadCache;
    }

    public static ZombieAISharder getZombieAISharder() {
        return zombieAISharder;
    }

    /**
     * Offloads an {@code IsoChunk.SafeWrite} invocation to the chunk I/O pool.
     * The {@code originalInvoker} must invoke the original SafeWrite method
     * (via reflection) on the worker thread; the ThreadLocal guard in
     * {@code ZMTOptAgent} prevents recursion.
     */
    public static void offloadChunkSave(int wx, int wy, ByteBuffer bb, Runnable originalInvoker) {
        if (chunkIOPool != null && originalInvoker != null) {
            chunkIOPool.submit("chunk-save", originalInvoker);
        } else if (originalInvoker != null) {
            originalInvoker.run();
        }
    }
}