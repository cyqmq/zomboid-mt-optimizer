-- ZMTOpt Java Bridge (server)
-- Supports two Java-layer loading modes:
--
--   MODE A (recommended): Java Agent (fully open-source)
--     Launch the game/server with:
--       -javaagent:/path/to/zmtopt-agent.jar=hook=zombie/game/GameServer.update
--     The agent initializes the optimization layer and hooks the game tick
--     automatically. Lua does NOT need to call Java in this mode.
--
--   MODE B: FJL / ZombieBuddy (closed-source, optional)
--     These loaders expose `luajava` to Lua; we bind FJLPlugin directly.
--
-- If neither mode is available, the mod still runs its Lua time-slicer.

local JavaBridge = {}
JavaBridge.available = false

-- Mode B: FJL/ZombieBuddy exposes luajava -> call Java directly.
local function tryLuajava()
    if luajava == nil then
        return false
    end
    local okClass, clazz = pcall(luajava.bindClass, "dev.zmtopt.bridge.FJLPlugin")
    if not okClass or clazz == nil then
        return false
    end
    local okInit, initErr = pcall(clazz.init, clazz)
    if not okInit then
        print("[ZMTOpt] FJLPlugin.init error: " .. tostring(initErr))
        return false
    end
    JavaBridge.available = true
    return true
end

function JavaBridge.init()
    if not ZMTOpt.Config.EnableJavaLayer then
        print("[ZMTOpt] Java layer disabled by config. Running Lua-only mode.")
        return
    end

    if tryLuajava() then
        print("[ZMTOpt] Java layer bound via luajava (FJL/ZombieBuddy mode).")
    else
        -- Mode A (javaagent) needs no Lua-side action: the agent already ran
        -- premain and hooked the tick. We just announce it.
        print("[ZMTOpt] luajava not available. "
            .. "If launched with -javaagent:zmtopt-agent.jar, the Java layer is active. "
            .. "Otherwise running Lua-only mode (time-slicing only).")
    end
end

function JavaBridge.onTick(currentTick)
    if not JavaBridge.available then
        return
    end
    local okClass, clazz = pcall(luajava.bindClass, "dev.zmtopt.bridge.FJLPlugin")
    if okClass and clazz ~= nil then
        pcall(clazz.onTick, clazz, currentTick)
    end
end

function JavaBridge.isAvailable()
    return JavaBridge.available
end

return JavaBridge