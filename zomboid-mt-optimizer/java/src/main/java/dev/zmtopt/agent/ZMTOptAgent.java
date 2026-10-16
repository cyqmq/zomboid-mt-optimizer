package dev.zmtopt.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Java agent entry point - the fully open-source replacement for
 * Faded Java Loader / ZombieBuddy.
 *
 * <p>Launch the game or dedicated server with:
 * <pre>
 *   -javaagent:/path/to/zmtopt-agent.jar=hook=zombie/network/ServerMap.preupdate,hook=zombie/iso/IsoWorld.update
 * </pre>
 *
 * <p>The agent uses the standard JDK {@link Instrumentation} API:
 * <ul>
 *   <li>{@link #premain} runs before the game's main method and starts the
 *       optimization layer ({@code FJLPlugin.init()}).</li>
 *   <li>A {@link ZMTOptTransformer} watches for game classes and injects a
 *       call to {@link #onGameTick()} into the server tick method
 *       ({@code ServerMap.preupdate}) and/or the client/single-player update
 *       method ({@code IsoWorld.update}), so the optimization layer is driven
 *       once per tick from the game thread on both ends.</li>
 *   <li>When {@code -Dzmtopt.asyncSave.enable=true}, the transformer redirects
 *       {@code IsoChunk.SafeWrite} to a background I/O thread pool
 *       (C2ME-style parallel chunk save). A {@link ThreadLocal} guard prevents
 *       recursion when the original method is invoked from the worker.</li>
 * </ul>
 */
public final class ZMTOptAgent {

    private static volatile long lastTickTime;
    private static volatile boolean initAttempted = false;

    // Async chunk save support (C2ME-style).
    private static final ThreadLocal<Boolean> IN_ASYNC_WRITE = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static volatile boolean asyncSaveEnabled = false;
    private static volatile Method originalSafeWrite;

    // Zombie AI hook diagnostic (config-gated scaffold).
    private static final AtomicLong zombieUpdateCount = new AtomicLong();
    private static volatile boolean zombieAIHookEnabled = false;

    private ZMTOptAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[ZMTOpt] javaagent premain (args=" + agentArgs + ")");
        asyncSaveEnabled = Boolean.getBoolean("zmtopt.asyncSave.enable");
        zombieAIHookEnabled = Boolean.getBoolean("zmtopt.zombieAI.hook");
        inst.addTransformer(new ZMTOptTransformer(agentArgs, asyncSaveEnabled, zombieAIHookEnabled));
        initPlugin();
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    /** Starts the optimization layer (idempotent). */
    public static void initPlugin() {
        if (initAttempted) {
            return;
        }
        initAttempted = true;
        try {
            Class<?> plugin = Class.forName("dev.zmtopt.bridge.FJLPlugin");
            Method init = plugin.getMethod("init");
            init.invoke(null);
        } catch (Throwable t) {
            System.err.println("[ZMTOpt] initPlugin failed: " + t);
        }
    }

    /** Called by the transformer when game classes start loading. */
    public static void onGameClassesLoaded() {
        try {
            Class<?> plugin = Class.forName("dev.zmtopt.bridge.FJLPlugin");
            Method onLoaded = plugin.getMethod("onGameClassesLoaded");
            onLoaded.invoke(null);
        } catch (Throwable ignored) {
            // Game classes are not present yet; retry later.
        }
    }

    /**
     * Called from the instrumented game update method (game thread).
     * A time-based guard prevents excessive draining when the hooked method
     * is invoked more frequently than once per game tick.
     */
    public static void onGameTick() {
        long now = System.currentTimeMillis();
        if (now - lastTickTime < 20L) {
            return;
        }
        lastTickTime = now;
        try {
            Class<?> plugin = Class.forName("dev.zmtopt.bridge.FJLPlugin");
            Method onTick = plugin.getMethod("onTick", long.class);
            onTick.invoke(null, now);
        } catch (Throwable ignored) {
            // Plugin not initialized yet; skip this tick.
        }
    }

    // ------------------------------------------------------------------
    // Async chunk save (C2ME-style parallel save I/O)
    // ------------------------------------------------------------------

    public static void setAsyncSaveEnabled(boolean enabled) {
        asyncSaveEnabled = enabled;
    }

    public static boolean isAsyncSaveEnabled() {
        return asyncSaveEnabled;
    }

    /**
     * Called by the injected bytecode at the start of
     * {@code IsoChunk.SafeWrite}. Returns true when the write should be
     * offloaded to a background thread. Returns false when we are already
     * inside the original method (invoked from the worker thread via
     * reflection), which lets the original body run and avoids recursion.
     */
    public static boolean shouldOffloadSave() {
        return asyncSaveEnabled && !Boolean.TRUE.equals(IN_ASYNC_WRITE.get());
    }

    /**
     * Called (from the injected bytecode) when {@link #shouldOffloadSave()}
     * returned true. Submits the original {@code SafeWrite} invocation to the
     * chunk I/O pool; the main thread returns immediately.
     */
    public static void onSafeWrite(int wx, int wy, ByteBuffer bb) {
        if (bb == null) {
            return;
        }
        try {
            Class<?> plugin = Class.forName("dev.zmtopt.bridge.FJLPlugin");
            Method offload = plugin.getMethod("offloadChunkSave", int.class, int.class, ByteBuffer.class, Runnable.class);
            offload.invoke(null, wx, wy, bb, (Runnable) () -> invokeOriginalSafeWrite(wx, wy, bb));
        } catch (Throwable t) {
            System.err.println("[ZMTOpt] onSafeWrite dispatch failed: " + t);
            invokeOriginalSafeWrite(wx, wy, bb);
        }
    }

    /** Invokes the original (already instrumented) SafeWrite from the worker. */
    private static void invokeOriginalSafeWrite(int wx, int wy, ByteBuffer bb) {
        IN_ASYNC_WRITE.set(Boolean.TRUE);
        try {
            if (originalSafeWrite == null) {
                Class<?> cls = Class.forName("zombie.iso.IsoChunk");
                originalSafeWrite = cls.getMethod("SafeWrite", int.class, int.class, ByteBuffer.class);
            }
            originalSafeWrite.invoke(null, wx, wy, bb);
        } catch (Throwable t) {
            System.err.println("[ZMTOpt] original SafeWrite invocation failed: " + t);
        } finally {
            IN_ASYNC_WRITE.remove();
        }
    }

    // ------------------------------------------------------------------
    // Zombie AI hook scaffold (diagnostic)
    // ------------------------------------------------------------------

    public static void setZombieAIHookEnabled(boolean enabled) {
        zombieAIHookEnabled = enabled;
    }

    public static boolean isZombieAIHookEnabled() {
        return zombieAIHookEnabled;
    }

    /**
     * Called from the injected {@code IsoZombie.update} methods when
     * {@code -Dzmtopt.zombieAI.hook=true}. Currently diagnostic only: counts
     * zombie AI updates. The next phase will consult
     * {@code ZombieAISharder} to skip out-of-shard updates.
     */
    public static void onZombieUpdateTick() {
        zombieUpdateCount.incrementAndGet();
    }

    public static long getZombieUpdateCount() {
        return zombieUpdateCount.get();
    }
}