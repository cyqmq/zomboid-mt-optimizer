# Zomboid MT Optimizer (ZMTOpt)

**Project Zomboid Build 42 · dual-end (server + client) multithreading, chunk I/O and single-port optimization · a fully open-source Java Agent**

Injects optimization code into the game JVM via the standard JDK `-javaagent` mechanism (`Instrumentation` API). No closed-source loader (FJL/ZombieBuddy) required.

> ⚠️ **Risk warning**: A Java Agent modifies game runtime behavior and may crash or corrupt saves. **Always back up your saves.** This mod is for learning/research purposes; the author is not responsible for data loss.

---

## Why not FJL?

FJL (Faded Java Loader) and ZombieBuddy are closed-source frameworks with unpublished APIs. Project Zomboid is a Java application, so the JDK's official `-javaagent` mechanism provides the same Java-code injection capability with fully auditable, open-source code.

| Aspect | FJL / ZombieBuddy | ZMTOpt Java Agent |
|---|---|---|
| Open source | ❌ | ✅ |
| Loading | closed API | standard `-javaagent` JVM arg |
| Capability | Java bytecode injection | same (`Instrumentation` + ASM) |
| Extra deps | framework install | none |

---

## Features

| Feature | How | Config |
|---|---|---|
| **Server tick hook** | ASM injects `onGameTick()` into `zombie/network/ServerMap.preupdate` | default on |
| **Client / single-player hook** | ASM injects into `zombie/iso/IsoWorld.update` | default on |
| **Thread-pool offload** | save serialization / chunk prep / callbacks moved off the main thread | `core.*` modules |
| **Chunk file warmup** | background pre-read into OS page cache (`ChunkReadWarmup`) | `-Dzmtopt.chunkIO.*` |
| **Chunk byte cache** | background pre-read into a bounded JVM-heap cache (`ChunkReadCache`) | `-Dzmtopt.chunkIO.*` |
| **Async chunk save** | `IsoChunk.SafeWrite` disk I/O offloaded to a worker pool (C2ME-style) | `-Dzmtopt.asyncSave.enable=true` (experimental) |
| **Zombie AI sharding** | distant zombies split into N shards, one shard updated per tick (frame-time distribution) | `-Dzmtopt.zombieAI.shards=4` |
| **Zombie AI shard-skip** | injects real skip logic into `IsoZombie.update`; out-of-shard distant zombies are skipped (experimental) | `-Dzmtopt.zombieAI.skip=true` (experimental) |
| **Zombie AI hook scaffold** | diagnostic counter injected into `IsoZombie.update` (no behavior change) | `-Dzmtopt.zombieAI.hook=true` (experimental) |
| **Single-port** | forces `udpPort = port` in `UdpEngine.<init>`, merging 16261+16262 into one | `-Dzmtopt.singlePort.enable=true` |

---

## Build

Requirements: JDK 17+ (verified with JDK 21, `--release 17`; game ships JRE 25). Internet access needed once for ASM + JUnit.

```bash
cd zomboid-mt-optimizer
gradle agentJar      # -> build/libs/zmtopt-agent.jar (fat jar with ASM)
gradle test            # run the JUnit test suite
```

Other artifacts: `gradle jar` → `build/libs/zmtopt.jar` (slim, for FJL/luajava mode).

---

## Install (Java Agent mode)

### Dedicated server (Linux)

Edit `StartServer64.sh`, add to the `java` launch command:

```bash
-javaagent:/path/to/zmtopt-agent.jar=hook=zombie/network/ServerMap.preupdate
```

### Client / single-player

Edit `ProjectZomboid64.json`, add to `vmArgs`:

```jsonc
"-javaagent:C:/path/to/zmtopt-agent.jar=hook=zombie/iso/IsoWorld.update",
```

### Both ends

```jsonc
"-javaagent:C:/path/to/zmtopt-agent.jar=hook=zombie/network/ServerMap.preupdate,hook=zombie/iso/IsoWorld.update",
```

### Verify

Logs should show:

```
[ZMTOpt] javaagent premain (...)
[ZMTOpt] chunk I/O optimizer ready (threads=2, radius=2, chunkSize=300).
[ZMTOpt] hooking zombie/network/ServerMap#preupdate()V
[ZMTOpt] hooking zombie/iso/IsoWorld#update()V
[ZMTOpt] single-port: forcing udpPort=port in zombie/core/raknet/UdpEngine
*** SERVER STARTED ****
server is listening on port 16261
```

---

## Configuration (JVM system properties)

| Property | Default | Description |
|---|---|---|
| `zmtopt.chunkIO.enable` | `true` | chunk I/O optimizer |
| `zmtopt.chunkIO.threads` | `2` | chunk I/O worker threads |
| `zmtopt.chunkIO.radius` | `2` | warmup radius in chunks |
| `zmtopt.chunkIO.chunkSize` | `300` | map chunk size in tiles (verify for your world) |
| `zmtopt.chunkIO.intervalTicks` | `40` | warmup pass interval |
| `zmtopt.asyncSave.enable` | `false` | async chunk save (experimental - back up saves) |
| `zmtopt.zombieAI.enable` | `true` | zombie AI sharder |
| `zmtopt.zombieAI.shards` | `4` | shard count |
| `zmtopt.zombieAI.closeRadius` | `15` | close radius (always simulate) |
| `zmtopt.zombieAI.hook` | `false` | zombie AI diagnostic hook (experimental) |
| `zmtopt.zombieAI.skip` | `false` | zombie AI shard-skip (experimental) |
| `zmtopt.singlePort.enable` | `false` | merge two UDP ports into one |

Lua-side settings mirror these in `42/media/lua/shared/ZMTOpt/ZMTOpt_Config.lua`.

---

## Verification status

- ✅ Compiled with `--release 17`; full JUnit test suite passes
- ✅ **Zombie AI shard-skip bytecode test**: with `-Dzmtopt.zombieAI.skip=true`, 8 zombies / 4 shards → exactly 2 `update()` calls per tick, others intercepted by injected skip logic
- ✅ **GitHub Actions CI** workflow (`.github/workflows/ci.yml`) builds the agent jar and runs tests on push
- ✅ **Real dedicated server test** (SteamCMD anonymous download, App 380870, Build 42.21.0, 3 GB heap):
  - `hooking zombie/network/ServerMap#preupdate()` — real class injected
  - `hooking zombie/iso/IsoWorld#update()` — real class injected
  - `hooking zombie/iso/IsoChunk#SafeWrite` — async-save real class injected
  - `single-port: forcing udpPort=port` — real `UdpEngine` injected
  - `*** SERVER STARTED ****` + `server is listening on port 16261` — **only one port bound** (verified in `/proc/net/udp`)

## Needs verification on your machine

1. Full gameplay with all optimizations enabled (≥6 GB RAM recommended for B42)
2. `zmtopt.chunkIO.chunkSize` actual value for your world
3. The zombie AI diagnostic hook (`zmtopt.zombieAI.hook=true`) then extending it to actually skip out-of-shard updates
4. Deeper core-simulation parallelization (zombie AI across cores, parallel chunk generation) is a larger engine-level effort (OptiZomb Full / C2ME scale)

---

## Disclaimer

Not affiliated with The Indie Stone or Faded Mods. `zombie.*` class names are referenced for modding research only. Java Agent injection carries risk; back up your saves.