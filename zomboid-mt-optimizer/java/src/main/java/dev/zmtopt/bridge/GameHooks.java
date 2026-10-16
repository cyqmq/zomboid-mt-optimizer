package dev.zmtopt.bridge;

/**
 * Bridge into the closed-source Project Zomboid engine.
 *
 * <p>All engine access happens through reflection. In the dedicated server
 * environment stdout is redirected into the server log file, so {@link #log}
 * simply writes to stdout/stderr - no fragile LuaManager binding required.
 *
 * <p>Verified class names (against the shipped dedicated server jar, Build
 * 42.21.0, 2026-10):
 * <ul>
 *   <li>{@code zombie.network.GameServer} - dedicated server entry (main)</li>
 *   <li>{@code zombie.network.ServerMap} - per-tick simulation driver</li>
 *   <li>{@code zombie.iso.IsoWorld} - client/single-player world update</li>
 *   <li>{@code zombie.core.raknet.UdpEngine} - network engine (two ports)</li>
 *   <li>{@code zombie.ChunkMapFilenames} - chunk file paths</li>
 * </ul>
 */
public final class GameHooks {

    public static final String CLASS_LUA_MANAGER = "zombie.Lua.LuaManager";
    public static final String CLASS_ISO_WORLD = "zombie.iso.IsoWorld";
    public static final String CLASS_GAME_SERVER = "zombie.network.GameServer";
    public static final String CLASS_SERVER_MAP = "zombie.network.ServerMap";
    public static final String CLASS_CHUNK_MAP_FILENAMES = "zombie.ChunkMapFilenames";

    private GameHooks() {
    }

    /** No-op; kept for API compatibility. Logging goes to stdout/stderr. */
    public static void init() {
        // stdout is captured by the game's log redirection.
    }

    /** Writes a message to the game log (stdout is redirected by the game). */
    public static void log(String message) {
        System.out.println(message);
    }

    /** Always true: stdout logging is always available. */
    public static boolean isBound() {
        return true;
    }

    /**
     * Returns the singleton instance of a game class through reflection.
     *
     * @param className      fully qualified class name
     * @param staticMethod   name of a static method returning the instance
     * @return the instance, or {@code null} if unavailable
     */
    public static Object getGameSingleton(String className, String staticMethod) {
        try {
            Class<?> cls = Class.forName(className);
            return cls.getMethod(staticMethod).invoke(null);
        } catch (Throwable t) {
            System.err.println("[ZMTOpt] getGameSingleton failed for "
                    + className + "." + staticMethod + ": " + t);
            return null;
        }
    }
}