-- ZMTOpt Server Hooks (server)
-- Registers server-side events: forwards ticks to the Java layer and runs
-- the Lua time-slicer so heavy script work does not stall a single tick.

require "ZMTOpt/ZMTOpt_Config"

local JavaBridge = require "ZMTOpt/ZMTOpt_JavaBridge"

-- ------------------------------------------------------------------
-- Lua time-slicer (works without Java)
-- ------------------------------------------------------------------
local sliceQueue = {}

local function scheduleLuaSlice(fn)
    if type(fn) == "function" then
        table.insert(sliceQueue, fn)
    end
end

local function drainLuaSlices()
    if not ZMTOpt.Config.EnableLuaTimeSlicing then
        return
    end
    local budget = ZMTOpt.Config.LuaSlicePerTick or 4
    local done = 0
    while done < budget and #sliceQueue > 0 do
        local fn = table.remove(sliceQueue, 1)
        local ok, err = pcall(fn)
        if not ok then
            print("[ZMTOpt] Lua slice error: " .. tostring(err))
        end
        done = done + 1
    end
end

-- ------------------------------------------------------------------
-- Server lifecycle hooks
-- ------------------------------------------------------------------
local function onServerStarted()
    JavaBridge.init()
end

local tickCounter = 0

local function onTick()
    tickCounter = tickCounter + 1
    JavaBridge.onTick(tickCounter)
    drainLuaSlices()
end

-- Save coordination: if the game exposes a pre-save event, hook it.
-- (Event names vary between builds; guard with a nil check.)
local function onPreSave()
    -- Lua-side: nothing heavy runs here; the Java layer offloads the
    -- serialization work to background threads.
end

-- ------------------------------------------------------------------
-- Registration
-- ------------------------------------------------------------------
Events.OnServerStarted.Add(onServerStarted)
Events.OnTick.Add(onTick)

if Events.OnPreSave ~= nil then
    Events.OnPreSave.Add(onPreSave)
end
if Events.OnGameSave ~= nil then
    Events.OnGameSave.Add(onPreSave)
end

print("[ZMTOpt] Server hooks loaded.")