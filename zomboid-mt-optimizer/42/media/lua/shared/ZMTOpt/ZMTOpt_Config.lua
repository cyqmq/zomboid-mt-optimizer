-- ZMTOpt Configuration (shared)
-- This file is loaded on both client and server so settings can be tuned
-- from one place. Server-side hooks read these values at startup.

ZMTOpt = ZMTOpt or {}
ZMTOpt.Config = ZMTOpt.Config or {}

-- ------------------------------------------------------------------
-- Java layer (requires Faded Java Loader / ZombieBuddy)
-- ------------------------------------------------------------------
ZMTOpt.Config.EnableJavaLayer = true      -- set false to force Lua-only mode
ZMTOpt.Config.WorkerThreads = 0           -- 0 = auto (CPU cores - 1)

-- Save offload: move serialization/database work off the main thread.
ZMTOpt.Config.EnableSaveOffload = true

-- Chunk preparation: precompute chunk data on background threads.
ZMTOpt.Config.EnableChunkPrep = true
ZMTOpt.Config.ChunkPrepMaxQueue = 64      -- backpressure limit

-- ------------------------------------------------------------------
-- Single-port optimization (Java layer, bytecode injection)
-- Merges the server's two UDP ports (default 16261 + 16262) into one by
-- forcing udpPort = port inside UdpEngine.<init>.
-- Enable via JVM system property:
--   -Dzmtopt.singlePort.enable=true
-- NOTE: test on a non-Steam server first; Steam servers may need both ports.
-- ------------------------------------------------------------------
ZMTOpt.Config.SinglePort = {
    Enable = false,   -- -Dzmtopt.singlePort.enable
}

-- ------------------------------------------------------------------
-- Async chunk save (C2ME-style, Java layer, bytecode injection)
-- Offloads IsoChunk.SafeWrite disk I/O from the main thread to the chunk
-- I/O pool. Experimental - enable only after backing up saves:
--   -Dzmtopt.asyncSave.enable=true
-- ------------------------------------------------------------------
ZMTOpt.Config.AsyncChunkSave = {
    Enable = false,   -- -Dzmtopt.asyncSave.enable (experimental)
}

-- ------------------------------------------------------------------
-- Chunk I/O optimizer (C2ME-style, Java layer)
-- Java layer reads these as JVM system properties (Lua cannot pass values
-- directly to Java). Add to your -javaagent JVM arguments:
--   -Dzmtopt.chunkIO.enable=true
--   -Dzmtopt.chunkIO.threads=2
--   -Dzmtopt.chunkIO.maxPending=128
--   -Dzmtopt.chunkIO.radius=2
--   -Dzmtopt.chunkIO.chunkSize=300
--   -Dzmtopt.chunkIO.intervalTicks=40
-- The defaults below document the intended values.
-- ------------------------------------------------------------------
ZMTOpt.Config.ChunkIO = {
    Enable = true,        -- -Dzmtopt.chunkIO.enable
    Threads = 2,          -- -Dzmtopt.chunkIO.threads  (I/O worker threads)
    MaxPending = 128,     -- -Dzmtopt.chunkIO.maxPending
    Radius = 2,           -- -Dzmtopt.chunkIO.radius   (warmup radius in chunks)
    ChunkSize = 300,      -- -Dzmtopt.chunkIO.chunkSize (map chunk size in tiles)
    IntervalTicks = 40,   -- -Dzmtopt.chunkIO.intervalTicks
}

-- Zombie AI throttling (distance-based LOD for AI updates).
ZMTOpt.Config.EnableZombieThrottle = true
ZMTOpt.Config.ZombieCloseRadiusTiles = 15 -- zombies within this radius always tick
ZMTOpt.Config.ZombieFarIntervalTicks = 10  -- far zombies update every N ticks

-- ------------------------------------------------------------------
-- Lua time-slicing (works WITHOUT the Java layer)
-- Spreads heavy Lua work across ticks so no single tick stalls.
-- ------------------------------------------------------------------
ZMTOpt.Config.EnableLuaTimeSlicing = true
ZMTOpt.Config.LuaSlicePerTick = 4         -- max heavy Lua tasks per tick