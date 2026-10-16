# ZMTOpt 技术调研报告

本报告梳理了市面上的 Project Zomboid 线程/性能优化 mod 与「3D 僵毁」类大改动 mod 的实现原理，作为 ZMTOpt 开发的技术依据。调研时间：2026-10。

---

## 1. 线程优化 mod 的实现方式（以 OptiZomb 为代表）

**OptiZomb**（optizomb.dev）是目前最知名的 PZ 性能优化补丁，官方定位是「非官方、未授权的补丁」。它直接点明了 PZ 的性能本质：

> "Project Zomboid wasn't built to handle massive hordes. The rendering code hammers the GPU with thousands of redundant calls per frame, and almost everything runs on a single CPU core."

### 1.1 单线程瓶颈

PZ 的引擎（Java + LWJGL）几乎所有模拟逻辑跑在**单核**上。这意味着：

- 僵尸寻路、碰撞、动画、AI 感知全部串行执行
- 僵尸数量越多，单核负载越高，表现为帧率/TPS 下降
- 渲染侧还有大量重复的 GPU 调用（每帧每个僵尸单独上传骨骼数据等）

### 1.2 OptiZomb Lite（20 项客户端优化，Build 41）

Lite 版只做**渲染/客户端侧**优化，不修改模拟线程，因此可以运行在任意原版服务器上：

| 优化项 | 原理 |
|---|---|
| 消除 GPU 同步停顿 | 移除开发遗留的每帧 GPU sync stall |
| 缓存 shader/texture/model 绑定 | 避免每只僵尸重复绑定资源 |
| 僵尸骨骼数据打包 | 把所有僵尸骨骼打包进**一个 GPU buffer**，替代成千上万次单独上传 |
| 缓存对象类型检查 | 墙/门/窗不再每帧重复识别类型 |
| 合并 draw call | 相同 texture atlas 的调用合并 |
| 地板渲染路径修复 | 8 项 CPU 侧修复；画面无变化时缓存地板渲染结果 |
| 跳过屏幕外血迹 | 不可见的血迹 splat 不绘制，缓存颜色查询 |
| 阴影批量绘制 | 按纹理批量绘制阴影，简化远景阴影 |
| 物品容器索引 | 搜索从线性遍历变为索引查询 |
| 僵尸距离 LOD | 按距离排序分级：远景僵尸跳过骨骼细节（手指/脚趾）、节流 AI/物理更新频率 |

### 1.3 OptiZomb Full（未发布）

Full 版宣称：

> "Spreads zombie simulation (movement, pathfinding, collision, animation) across all your CPU cores. Includes 18 thread-safety fixes for vanilla bugs."

也就是把**僵尸模拟分布到多核**，并修复 18 个原版线程安全 bug。客户端和服务端必须同版本运行。

### 1.4 关键技术手法：二进制补丁 + 自研 Java 类

OptiZomb 的安装器使用 **bsdiff 二进制差分**直接修改游戏反编译出的 vanilla class 文件，并叠加自己编写的 Java 类（`OptiZomb*.java`）。关键点：

- **新增代码开源**（GitHub），但**补丁源码不公开**——因为补丁包含反编译的 Indie Stone 代码，涉及版权
- 安装器把原版类文件的二进制 diff 打到游戏 jar 上
- 它明确警告：修改核心行为，可能崩溃/坏档，需备份存档

**结论：想真正多线程化核心模拟，必须直接修改/注入游戏自己的 Java 类。标准 Lua mod 做不到。**

---

## 2. 「3D 僵毁」类大改动 mod 的实现方式

### 2.1 PZ3D（kolpaque，2026-09）

- 把俯视角相机改为第一/第三人称，本质是**替换渲染管线**
- 需要在游戏 JVM 中**加载自定义 Java 字节码**、修改引擎行为，超出标准 Lua modding 能力
- 依赖 **ZombieBuddy** 框架提供该能力
- 当前仅支持单机（Build 42.20.4），计划支持多人

作者原话（ixbt.games 引用）：

> "PZ3D needs to load Java code and modify engine behavior beyond the capabilities of standard modding tools. ZombieBuddy provides such access. Replacing it would require another mechanism with similar capabilities and associated risks."

### 2.2 Project Viewpoint（另一个 3D 图形引擎 mod）

- 同样基于 ZombieBuddy，支持多人，更成熟
- 配套 6000+ 个 3D 模型包、物理命中盒等
- 社区反馈：需要 RTX 2060 级别显卡，第三人称在城市仍会掉帧

### 2.3 The Dark Crow 团队

- 也在做 3D 化，尚未公开可用版本

### 2.4 结论

> **大改动 = Java 字节码注入 + 引擎类挂钩。** 这就是 Faded Java Loader（FJL）、ZombieBuddy 这类 Java 加载器存在的意义。

---

## 3. Build 42 的 Java 注入框架

### 3.1 Faded Java Loader（FJL）

- Build 42 的 Java mod 加载器，当前版本 0.2.0+
- 由 Faded Mods（fadedmods.com）分发，**闭源**，无公开 API 文档仓库
- 生态内的 Java 大型 mod：Echoes of Humanity（NPC 系统）、Knox Reborn、Common Sense Reborn 等
- mod 通过 FJL 加载 Java 代码，实现超越 Lua 能力的引擎级修改

### 3.2 ZombieBuddy

- 另一个 Java 注入框架，PZ3D / Project Viewpoint 依赖它
- 同样闭源，通过 Steam Workshop 分发

### 3.3 对 ZMTOpt 的影响

由于 FJL/ZombieBuddy 均闭源且 API 未公开，本项目的 Java 层采用**纯 JDK + 反射**设计：

- 线程池、调度器、任务队列等核心组件零外部依赖，可独立编译测试
- 所有对游戏类（`zombie.*`）的访问通过反射完成，类名基于社区公开知识，需按实际游戏版本/FJL 映射校验
- Lua 桥接层覆盖 FJL/ZombieBuddy 的常见加载约定（`loadJar`/`require`/`loadClass`/`luajava`），并优雅降级到纯 Lua 模式

---

## 4. Build 42 服务器性能事实（来自社区指南）

- B42 是引擎大重构：32 层垂直空间、Liquid Logic Engine（高人口区域 CPU 开销比 B41 降低约 60%）、预测性区块缓存
- B42 服务器内存需求显著上升：基础约 6 GB，每人 +0.5 GB，重度 mod 服建议 12-16 GB
- 默认 `-Xmx3072m` 对 B42 明显不足，OOM 会表现为区块加载黑块/卡顿
- SQLite 数据库需要定期 VACUUM，长时间运行后性能下降
- `loadstring`/`loadstream` 曾在 42.20.4 临时禁用（安全加固），42.21 已恢复

### 4.1 服务器配置推荐（server.ini）

以下参数可显著降低服务器负载（均为官方配置项）：

```ini
# 世界清理：血渍保留天数（0 = 永久保留，建议 3-7 天）
BloodSplatLifespanDays=3

# 玩家尸体清理
RemovePlayerCorpsesOnCorpseRemoval=true

# 空服暂停（节省 CPU）
PauseEmpty=true

# 车辆引擎噪音吸引僵尸的距离（降低可减少 AI 计算）
CarEngineAttractionModifier=0.5

# 僵尸重生/密度（按需调整）
ZombieRespawn=false
```

### 4.2 JVM 参数推荐（ProjectZomboid64.json / StartServer64.sh）

```jsonc
"-Xms4g",
"-Xmx10g",                 // 按服务器内存调整
"-XX:+UseZGC",            // 低停顿 GC，减少卡顿
"-XX:+AlwaysPreTouch",
"-XX:ZUncommitDelay=300",
"-XX:-CreateCoredumpOnCrash"
```

> 注意：`ProjectZomboid64.json` 只影响客户端；专用服务器用 `StartServer64.bat/sh` 自己的 `-Xmx`。

---

## 5. 调研结论汇总

| 目标 | 标准 Lua mod 能否做到 | 需要 Java 注入 |
|---|---|---|
| 存档卡顿优化（序列化卸载） | 部分（时序调整） | ✅ 线程池卸载 |
| 区块加载卡顿优化 | 部分（缓存/预取） | ✅ 后台线程预计算 |
| 僵尸/寻路卡顿优化 | 部分（LOD 节流） | ✅ 模拟线程并行化 |
| 服务器整体 TPS | 配置层优化 | ✅ 核心修复 |
| 渲染优化（客户端） | 否 | ✅（OptiZomb 路线） |

ZMTOpt 采用**分层策略**：Java 注入层做真正的主线程卸载，Lua 层提供时间切片降级方案，配置层提供立即可用的优化。

---

## 6. 开源替代方案：自研 Java Agent（本项目采用）

FJL 和 ZombieBuddy 均闭源，API 不公开。但 PZ 是 Java 应用，JDK 官方提供 **`-javaagent` + `Instrumentation` API**，可达到相同的 Java 代码注入能力，完全开源：

### 6.1 原理

- 在 JVM 启动参数加 `-javaagent:zmtopt-agent.jar=hook=...`
- JVM 在 `main` 方法执行前调用 agent 的 `premain(String, Instrumentation)`
- `Instrumentation.addTransformer(...)` 注册 `ClassFileTransformer`，在游戏类加载时用 **ASM** 修改字节码
- 把 `ZMTOptAgent.onGameTick()` 注入到服务器主循环 `update()` 方法开头，从而在游戏线程驱动优化层

### 6.2 开源工具链

- **ASM**（`org.ow2.asm:asm`，BSD 协议）：字节码操作库，用于方法注入
- **pz-zmod**（GitHub `cocolabs/pz-zmod`，MIT 协议）：PZ mod 开发环境，提供 `decompileZomboid` 任务反编译游戏类，用于校验真实的类名/方法名
- **pz-zdoc / ZomboidDoc**（GitHub `cocolabs/pz-zdoc`，GPL-3.0）：Lua API 文档生成

### 6.3 与 FJL/ZombieBuddy 对比

| 对比项 | FJL / ZombieBuddy | 自研 Java Agent |
|---|---|---|
| 开源 | ❌ | ✅ |
| 加载机制 | 闭源 API | JDK 标准 `-javaagent` |
| 字节码注入 | 提供 | `Instrumentation` + ASM 自己实现 |
| 主线程 tick 驱动 | 框架桥接 Lua→Java | ASM 注入游戏 `update()` |
| 风险 | 框架本身闭源不可审计 | 代码全部可审计 |

### 6.4 局限

- 需要修改启动参数（`ProjectZomboid64.json` / `StartServer64.sh`）
- 深度并行化（如僵尸模拟跨核）仍需对核心类做线程安全补丁，这属于独立的大工程

> 已用反编译源码核实的 B42 钩子：`zombie/network/ServerMap.preupdate()`（服务器每 tick 调用一次，作为 agent 注入点）。

**结论：Java Agent 是 FJL 的可行开源替代，本项目的 `agent/` 包即基于此实现。**