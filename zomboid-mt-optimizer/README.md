# Zomboid MT Optimizer (ZMTOpt)

**Project Zomboid Build 42 · 双端多线程 + 区块 I/O 优化 mod（纯开源 Java Agent 方案）**

通过标准 JDK 的 `-javaagent` 机制（`Instrumentation` API）把优化代码注入游戏 JVM：

- **服务端**：存档序列化、僵尸 AI 更新等重活从主模拟线程卸载到后台线程池
- **客户端/单机端**：`IsoWorld.update` 每帧驱动优化层
- **区块 I/O（借鉴 C2ME 思路）**：后台线程池预热区块文件到 OS 页缓存，降低流式加载磁盘延迟
- **异步区块存档（C2ME 式多线程）**：`IsoChunk.SafeWrite` 磁盘写从主线程卸载到 I/O 线程池，消除存档卡顿
- **单端口优化**：字节码注入强制 `udpPort = port`，服务端两个 UDP 端口（16261+16262）合并为一个

**不依赖任何闭源加载器（FJL/ZombieBuddy）**。

> ⚠️ **风险提示**：Java Agent 会修改游戏运行时行为，可能崩溃或导致存档损坏。**使用前务必备份存档**。本 mod 为学习/研究性质，作者不对数据丢失负责。

---

## 目录结构

```
zomboid-mt-optimizer/
├── common/mod.info                     # B42 通用 mod 清单
├── 42/
│   ├── mod.info                        # B42 版本清单
│   └── media/lua/
│       ├── shared/ZMTOpt/ZMTOpt_Config.lua      # 配置
│       └── server/ZMTOpt/
│           ├── ZMTOpt_JavaBridge.lua           # 兼容 FJL/luajava（可选）
│           └── ZMTOpt_ServerHooks.lua          # 服务器事件钩子 + Lua 时间切片
├── java/                               # Java 源码（Gradle 项目）
│   └── src/main/java/dev/zmtopt/
│       ├── agent/                       # Java Agent 入口 + ASM 字节码钩子
│       ├── core/                        # 线程池/调度器/主线程分发
│       ├── optimize/                    # 存档/区块/僵尸AI/性能 优化模块
│       └── bridge/                      # 插件入口 + 反射游戏钩子
└── RESEARCH.md                         # 市面实现方式调研报告
```

---

## 工作原理

```
启动：java -javaagent:zmtopt-agent.jar=hook=zombie/network/ServerMap.preupdate,hook=zombie/iso/IsoWorld.update ...
  │
  ▼
premain() ──► 注册 ClassFileTransformer ──► 初始化 FJLPlugin
  │                                          ├─ 线程池（多线程卸载）
  │                                          └─ 区块 I/O 池（预热）
  ▼
服务端 zombie/network/ServerMap.preupdate() 每 tick 调用 onGameTick()
客户端/单机 zombie/iso/IsoWorld.update()      每帧调用 onGameTick()
  │
  ▼
onGameTick() ──► FJLPlugin.onTick()
                  ├─ 排空主线程分发器（后台任务结果安全回调）
                  ├─ 驱动 Tick 调度器（错峰执行）
                  ├─ 驱动区块文件预热（OS 页缓存）
                  └─ 记录性能指标
```

---

## 功能模块

### Java Agent 层（核心，纯开源）

| 模块 | 类 | 作用 |
|---|---|---|
| Agent 入口 | `agent.ZMTOptAgent` | `premain`/`agentmain`，启动优化层，转发游戏 tick |
| 字节码钩子 | `agent.ZMTOptTransformer` | ASM 注入 `onGameTick()`，默认挂服务端 + 客户端双端 |
| 优先级线程池 | `core.MTThreadPool` | 命名后台线程池，按优先级调度，满载 CallerRuns 兜底 |
| 主线程分发 | `core.MainThreadDispatcher` | 后台结果安全回到游戏主线程执行 |
| Tick 调度器 | `core.TickScheduler` | 按 tick 间隔 + 相位偏移调度，避免同 tick 峰值 |
| 存档卸载 | `optimize.SaveOffloadManager` | 存档序列化/数据库写入放到后台线程 |
| 区块准备 | `optimize.ChunkPrepWorker` | 后台预计算区块数据（背压限制） |
| 僵尸 AI 节流 | `optimize.ZombieAIThrottler` | 距离 LOD：近距离每 tick，远距离降频 |
| **区块 I/O 池** | `optimize.ChunkIOPool` | 专用有界 I/O 线程池（C2ME 式并行 I/O） |
| **区块文件预热** | `optimize.ChunkReadWarmup` | 后台预读区块文件到 OS 页缓存，降低流式加载延迟 |
| 性能监控 | `optimize.ServerPerfMonitor` | 环形缓冲记录 tick 耗时 |
| 反射钩子 | `bridge.GameHooks` | 反射访问 `zombie.*` 引擎类 |
| 插件入口 | `bridge.FJLPlugin` | 初始化/onTick/onGameClassesLoaded |

### Lua 层（无 Java 也能用）

- **时间切片**：重型 Lua 计算分散到多个 tick
- **Java 桥**：兼容 FJL/luajava（可选），agent 模式自动识别
- **配置共享**：客户端/服务器共读 `ZMTOpt.Config`

---

## 构建

环境要求：JDK 17+（本机用 JDK 21 + `--release 17` 验证）。需要联网下载一次 ASM。

```bash
cd zomboid-mt-optimizer
gradle agentJar
# 产物：build/libs/zmtopt-agent.jar（fat jar，含 ASM）
```

其它产物：
- `gradle jar` → `build/libs/zmtopt.jar`（不含 ASM 的精简 jar，用于 FJL/luajava 模式）

---

## 安装（Java Agent 模式）

### 1. 专用服务器（Linux）

编辑 `StartServer64.sh`，在 `java` 启动命令的参数中加入：

```bash
-javaagent:/path/to/zmtopt-agent.jar=hook=zombie/network/ServerMap.preupdate
```

### 2. 客户端 / 单机（自建主机 / 单机）

编辑游戏安装目录的 `ProjectZomboid64.json`，在 `vmArgs` 数组中增加一行：

```jsonc
"-javaagent:C:/path/to/zmtopt-agent.jar=hook=zombie/iso/IsoWorld.update",
```

**双端同跑**（服务端 + 客户端共用）时两个钩子都加：

```jsonc
"-javaagent:C:/path/to/zmtopt-agent.jar=hook=zombie/network/ServerMap.preupdate,hook=zombie/iso/IsoWorld.update",
```

### 3. 验证

启动后控制台/日志出现以下输出即成功：

```
[ZMTOpt] javaagent premain (...)
[ZMTOpt] initialized with N worker threads.
[ZMTOpt] chunk I/O optimizer ready (threads=2, radius=2, chunkSize=300).
[ZMTOpt] hooking zombie/network/ServerMap#preupdate()V      ← 服务端
[ZMTOpt] hooking zombie/iso/IsoWorld#update()V               ← 客户端/单机
```

---

## 配置

### agent 钩子参数

`-javaagent` 参数格式：`hook=类名.方法名`，可多个，逗号分隔。

默认值（已对照反编译源码核实，B42）：

| 端 | 钩子 |
|---|---|
| 服务端 | `zombie/network/ServerMap.preupdate`（每 tick 一次） |
| 客户端/单机 | `zombie/iso/IsoWorld.update`（每帧一次） |

### 单端口优化（JVM 系统属性）

PZ 专用服务器默认使用两个 UDP 端口：`16261`（Steam 连接）和 `16262`（UDPRakNet 游戏流量）。单端口优化通过字节码注入把 `UdpEngine` 构造器中的 `udpPort` 强制覆盖为 `port`，使服务器只监听一个端口：

```
-Dzmtopt.singlePort.enable=true
```

完整示例：
```bash
-javaagent:/path/to/zmtopt-agent.jar=hook=zombie/network/ServerMap.preupdate -Dzmtopt.singlePort.enable=true
```

> ⚠️ 建议先在**非 Steam 模式**（`-nosteam`）服务器上验证；Steam 模式可能依赖第二个端口。等价的手工方案：在 `servertest.ini` 中把 `UDPPort` 设为与游戏端口相同（如 `UDPPort=16261`）。

### 异步区块存档（JVM 系统属性，实验性）

把 `IsoChunk.SafeWrite` 的磁盘 I/O 从主线程卸载到 `ChunkIOPool`（C2ME 式并行存档）。ThreadLocal 防护避免递归：

```
-Dzmtopt.asyncSave.enable=true
```

完整示例：
```bash
-javaagent:/path/to/zmtopt-agent.jar -Dzmtopt.singlePort.enable=true -Dzmtopt.asyncSave.enable=true
```

> ⚠️ 实验性功能：会改变存档写入时序。**启用前务必备份存档**，先在小规模服务器验证。

### 区块 I/O 配置（JVM 系统属性）

Java 层无法直接读 Lua 配置，区块 I/O 参数通过 `-D` 系统属性设置（加在 `-javaagent` 同一行）：

| 系统属性 | 默认 | 说明 |
|---|---|---|
| `-Dzmtopt.chunkIO.enable` | `true` | 是否启用区块 I/O 优化 |
| `-Dzmtopt.chunkIO.threads` | `2` | I/O 工作线程数 |
| `-Dzmtopt.chunkIO.maxPending` | `128` | 队列上限（背压） |
| `-Dzmtopt.chunkIO.radius` | `2` | 玩家周围预热半径（区块数） |
| `-Dzmtopt.chunkIO.chunkSize` | `300` | 地图区块尺寸（格），按需调整 |
| `-Dzmtopt.chunkIO.intervalTicks` | `40` | 预热扫描间隔（tick） |

### Lua 配置（`42/media/lua/shared/ZMTOpt/ZMTOpt_Config.lua`）

```lua
ZMTOpt.Config.EnableJavaLayer = true       -- 是否启用 Java 层
ZMTOpt.Config.WorkerThreads = 0            -- 0 = 自动（CPU 核数 - 1）
ZMTOpt.Config.EnableSaveOffload = true     -- 存档卸载
ZMTOpt.Config.EnableChunkPrep = true       -- 区块预计算
ZMTOpt.Config.EnableZombieThrottle = true  -- 僵尸 AI 节流
ZMTOpt.Config.ZombieCloseRadiusTiles = 15
ZMTOpt.Config.ZombieFarIntervalTicks = 10
ZMTOpt.Config.EnableLuaTimeSlicing = true  -- Lua 时间切片
ZMTOpt.Config.LuaSlicePerTick = 4
```

---

## 配合使用的服务器优化（官方配置）

见 `RESEARCH.md` 第 4 节。核心几条：

- `server.ini`：`BloodSplatLifespanDays=3`、`PauseEmpty=true`、`CarEngineAttractionModifier=0.5`
- JVM：`-Xms4g -Xmx10g -XX:+UseZGC`
- 定期对服务器 SQLite 数据库执行 VACUUM

---

## 已验证内容

- ✅ Java 核心编译通过（JDK 21，`--release 17`）
- ✅ 冒烟测试：线程池、存档卸载、僵尸节流、Tick 调度、区块背压
- ✅ **双端钩子测试**：`-javaagent` 默认同时注入 `ServerMap.preupdate` 和 `IsoWorld.update`，tick 驱动成功
- ✅ **区块 I/O 初始化**：`ChunkIOPool`/`ChunkReadWarmup` 创建成功，反射不可用时优雅降级
- ✅ **单端口注入测试**：`-Dzmtopt.singlePort.enable=true` 下 `UdpEngine` 的 `udpPort` 被强制为 `port`（16262→16261）
- ✅ **异步存档测试**：`-Dzmtopt.asyncSave.enable=true` 下 `IsoChunk.SafeWrite` 从主线程卸载到 `ZMTOpt-ChunkIO-worker` 线程执行，主线程立即返回，ThreadLocal 防递归生效
- ✅ **真实专用服务器验证**（SteamCMD 匿名下载 App 380870，Build 42.21.0）：
  - agent `premain` 正常加载、插件初始化、区块 I/O 模块就绪
  - `[ZMTOpt] hooking zombie/iso/IsoWorld#update()V` —— 真实 Java 25 类文件注入成功（ASM 9.11）
  - `[ZMTOpt] hooking zombie/network/ServerMap#preupdate()V` —— 真实服务端主循环注入成功
  - `[ZMTOpt] single-port: forcing udpPort=port in zombie/core/raknet/UdpEngine` —— 真实网络类注入成功
  - 服务器完整启动被**环境内存**（B42 需 ~6GB，本机仅 ~237MB 可用）阻止，非 mod 问题

## 待你在真实环境验证

1. ✅ **钩子类名/方法名**——已用反编译源码 + 真实服务器 jar（42.21.0）双重核实
2. ✅ **agent 字节码注入**——已在真实服务器进程上验证 `IsoWorld`/`ServerMap`/`UdpEngine` 三个类均注入成功
3. **服务器完整运行**——本环境内存不足（B42 需 ~6GB），在你的机器上跑通 `SERVER STARTED` 后观察 `onGameTick` 是否驱动优化层（日志出现 `TICK` 相关输出）
4. **`-Dzmtopt.chunkIO.chunkSize` 实际值**——PZ 地图区块尺寸需按你的世界确认（默认 300 为常用值，错了只是预热文件不对，无副作用）
5. **存档序列化/区块准备的实际游戏 API**（`SaveOffloadManager`/`ChunkPrepWorker` 通过 `ChunkDataProvider` 桥接，需要你按反编译源码实现具体的数据拷贝逻辑）
6. **核心模拟的真正跨核并行**（当前做主线程辅助任务卸载 + I/O 预热；僵尸模拟/区块生成的深度并行化需更深的线程安全补丁工程）

---

## 免责声明

本 mod 与 The Indie Stone、Faded Mods 无任何关联。`zombie.*` 类名仅为技术研究引用。Java Agent 注入有风险，使用前请备份存档。