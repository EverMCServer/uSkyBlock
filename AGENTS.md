# AGENTS.md

本文件为在此仓库中工作的 AI 编码代理提供指导。

> 注意：仓库里其他 README/文档类文件（`README.md`、`.github/`、各子模块自带的说明）都属于**原始上游 uSkyBlock 仓库**的遗留内容，与本仓库当前的定制化工作基本无关，不要以它们为准。`.github/workflows/build.yml` 带 `github.repository_owner == 'uskyblock'` 的守卫、`.github/deploy-*.sh` 是往 `uskyblock.ovh` 发 maven 产物的旧脚本，**在本仓库都不会生效**，不要照它们理解部署流程。本文件、`END-DESIGN.md`、`END-P0-CHECKLIST.md` 才是当前工作的说明。

## 项目概况

这是在开源插件 [uSkyBlock](https://github.com/rlf/uSkyBlock) 基础上深度定制的 Minecraft 海岛生存服务端插件，运行于 **Paper 26.2**（`everisland` 分支）。项目在原始空岛玩法上增加了大量扩展特性：毒水系统、硫磺系统、祭坛系统、下界改造、试炼刷怪笼转换、实体跨边界保护等。

- **原始仓库:** `https://github.com/rlf/uSkyBlock`
- **组 ID:** `ovh.uskyblock`
- **版本:** `3.2.0-SNAPSHOT`（Gradle）；`plugin.yml` 中写死的 `version` 是历史遗留的 `cong`，实际以 Gradle 版本为准
- **主包名:** `us.talabrek.ultimateskyblock`
- **许可证:** GPLv3
- **编译目标:** Java 25（根 `build.gradle.kts` 中 `sourceCompatibility/targetCompatibility = VERSION_25`）；`plugin.yml` 的 `api-version: 1.21.1` 是历史值，未随服务端升级
- **Gradle 脚本语言:** Kotlin 2.1.10

## 构建命令

```bash
# 编译整个项目
./gradlew build

# 仅编译核心模块
./gradlew :uSkyBlock-Core:build

# 打包为可部署 JAR（输出 uSkyBlock-Plugin/build/libs/）
./gradlew :uSkyBlock-Plugin:buildPlugin

# 运行测试
./gradlew :uSkyBlock-Core:test

# 运行单个测试类
./gradlew :uSkyBlock-Core:test --tests "us.talabrek.ultimateskyblock.xxx.XxxTest"

# 清理构建产物
./gradlew clean
```

Windows 下用 `gradlew.bat`（本机没有 `pwsh`，`gradlew` 需要 Java 25；Gradle wrapper 为 9.5.1）。`buildPlugin` 的产物固定为 **`uSkyBlock-Plugin/build/libs/uSkyBlock-Plugin.jar`**（约 38 MB，ShadowJar 合并全部模块）。

**注意:** 根 `build.gradle.kts` 中 `tasks.withType<Test>().configureEach { enabled = false }` 默认禁用了**所有**测试任务（包括 `:uSkyBlock-Core`）。要运行测试，需临时移除那 3 行（或用 init script 重新启用），跑完再恢复。测试依赖（JUnit 4 + Mockito + Hamcrest，`uSkyBlock-Core/build.gradle.kts`）仅在 `:uSkyBlock-Core` 子模块中配置，`uSkyBlock-Core/src/test/java/` 下有 33 个文件、其中 30 个含 `@Test`/`TestCase`。

## 权限与推送约定

### 权限：「完全权限」只为 git / gradlew 开

- `git` 操作（commit、branch、fetch、拉取、推送等）和 `gradlew` 构建需要额外权限，用户通常**以「完全权限」模式开启会话**，但这只是为了让这两类操作能顺利进行。
- **「完全权限」不等于放开一切写入。** 除 git / gradlew 之外，任何**工作区（本仓库根目录）以外**的写入、覆盖、删除操作，仍然要先征求用户意见；工作区内的常规改动不受此限。

### 推送：默认不推，推送即部署

本工作区涉及两个被流水线接管的仓库，**一次 push 就会跑流水线，让测试服立刻更新**：

| 仓库 | 远程 / 当前分支 | 推送后果 |
|------|-----------------|----------|
| 插件代码（本仓库根目录） | `origin` = `github.com:EverMCServer/uSkyBlock.git`，`everisland` | 测试服通过仓库 hook 自动拉取并替换 jar（日志里能看到 `[Rcon] 开始更新空岛主插件配置（commit: ...）`） |
| 插件运行配置 `uSkyBlock/`（独立仓库） | `origin` = `codeup.aliyun.com:evermc/acidisland2/uSkyBlock.git`，`test` | 配置流水线把 `plugins/uSkyBlock/` 同步到测试服 |

因此：

- **除非用户明确要求推送，否则一律不要 `git push`**（两个仓库都算）。改完留在工作区、或按用户要求本地 commit 即可，由用户自己决定何时推送、何时上线。
- 也不要替用户做会反过来影响测试服的操作：`push --force`、`commit --amend` 已推送的提交、删除远端分支等。
- 另一处注意：`acidisland-recipes/` 是被主仓库 `.gitignore` 忽略的普通目录（本身也不是 git 仓库），推送不会带上它，改了它需要另行同步。

## 项目架构

### 多模块结构

| 模块 | 用途 |
|------|------|
| `uSkyBlock-Core` | **核心模块**，包含所有业务逻辑、事件监听、岛屿管理、GUI、命令 |
| `uSkyBlock-API` | 旧版公开 API（v1），第三方插件通过此 API 获取岛屿等级/排名等信息 |
| `uSkyBlock-APIv2` | 新版 API（v2），推荐外部使用 `UltimateSkyblockProvider.get()` 获取 |
| `uSkyBlock-Plugin` | **装配模块**，通过 ShadowJar 将所有模块打包为可部署的 `uSkyBlock-Plugin.jar`（`uSkyBlock-Plugin/build/libs/`） |
| `uSkyBlock-FAWE` | FastAsyncWorldEdit 集成适配器 |
| `bukkit-utils` | 通用 Bukkit 工具库（命令框架、动画、文件/YAML 工具） |
| `po-utils` | gettext `.po` 文件解析和国际化工具（`I18nUtil`） |

### 依赖注入架构

核心模块使用 **Google Guice**：

- **`bootstrap/SkyblockModule`** — Guice 模块，绑定所有主要服务（`uSkyBlock`、`Plugin`、`LevelLogic`、`PlayerDB`、`Scheduler` 等）
- **`bootstrap/SkyblockApp`** — 顶层编排器，注入 `Services`、`Commands`、`Listeners`
- **`bootstrap/Services`** — 生命周期管理：`startup()` → `delayedEnable()` → `shutdown()`
- **`bootstrap/Listeners`** — 按 `Settings` / `config.yml` 条件注册所有事件监听器（**新增监听器必须在这里注册**）
- **`bootstrap/Commands`** — 注册命令

事件监听器均标注 `@Singleton`，通过构造函数注入 `uSkyBlock plugin` 和其他依赖。注意几个常用注入协作对象：`WorldManager`、`Scheduler`、`HookManager`、`PluginConfig`、`TeleportLogic`、`IslandLogic`。

### 核心包结构 (`uSkyBlock-Core/src/main/java/us/talabrek/ultimateskyblock/`)

| 包 | 职责 |
|----|------|
| `event/` | **Bukkit 事件监听器（17 个文件）** — 主要修改区域 |
| `island/` | 岛屿核心逻辑：`IslandInfo`、`IslandLogic`、`IslandGenerator`、`IslandLocatorLogic`、`BlockLimitLogic`、`LimitLogic`、`OrphanLogic`、`island/level/`、`island/task/` |
| `player/` | 玩家管理：`PlayerInfo`、`PlayerLogic`、`PerkLogic`、`TeleportLogic` |
| `challenge/` | 挑战/任务系统（`challenges.yml` 驱动） |
| `command/` | 命令框架和具体命令实现 |
| `handler/` | 外部插件集成（`WorldGuardHandler`、`WorldEditHandler`、`AsyncWorldEditHandler`、PlaceholderAPI） |
| `hook/` | 外部插件 Hook（经济、`hook/world/MultiverseHook`） |
| `world/` | 世界管理（**`WorldManager`**、`ChunkRegenerator`、`AcidIslandChunkGenerator`、`AcidNetherChunkGenerator`、`AcidEndChunkGenerator`、`AcidBiomeProvider`、`SingleBiomeProvider`） |
| `bootstrap/` | Guice DI 启动配置 |
| `gui/` `menu/` | GUI 菜单系统 |
| `util/` | 工具类（`LocationUtil`、`Scheduler`、`MaterialUtil`、`PluginInfo` 等） |
| `progress/` | 玩家进度追踪系统 |
| `uuid/` | 玩家 UUID/名称数据库（file/memory/bukkit 三种实现） |
| `biome/` | 生物群系配置与 GUI |
| `chat/` `signs/` | 聊天与木牌系统 |

### 事件目录 (`event/`) — 17 个监听器

| 文件 | 大小 | 功能 |
|------|------|------|
| `AltarEvents.java` | 71KB | **祭坛系统** — 多种祭坛（收获、战争、财富、灵魂、探索），Buff、附魔机制。仅作用于 `isSkyWorld()`（主世界） |
| `PlayerEvents.java` | 52KB | **玩家交互** — 考古可疑方块、传送门拦截、试炼刷怪笼、熔岩保护、访客保护等 |
| `SulfurEvents.java` | 26KB | **硫磺系统** — 硫磺方块/立方体相关机制 |
| `IslandBorderEvent.java` | 22KB | **跨边界实体保护** — 防止物品/生物/载具/掉落方块穿越岛屿边界 |
| `SpawnEvents.java` | 17KB | **刷怪管理** — 刷怪上限、生物生成控制、试炼刷怪笼转换（含幻翼开关） |
| `ToxicEvents.java` | 16KB | **毒水系统** — 酸性水扩散与伤害（区块 PDC 存储） |
| `EndPortalEvents.java` | 16KB | **末地传送** — `PlayerPortalEvent(END_PORTAL)` 进入方向改写到本岛到达点、`EntityPortalEvent(ENDER)` 双向改写（进末地落本岛、离末地落本岛返回点）、到达点 5×5 平台重建并按挖掘掉落、末影珍珠跨区域拦截。**不处理末影龙/龙战**。仅在 `Settings.end_enabled` 时注册 |
| `NetherTerraFormEvents.java` | 12KB | **下界改造** — 破坏方块时按概率转换/生成方块，猪灵→凋灵骷髅/烈焰人；仅 `isSkyNether()` |
| `GriefEvents.java` | 8KB | **防破坏** — 苦力怕、凋零、践踏、剪羊毛、孵化等 |
| `ExploitEvents.java` | 6KB | **防漏洞利用** — 传送门滥用、村民交易、他人岛屿骑乘 |
| `ItemDropEvents.java` | 5KB | **物品掉落保护** — 限制物品掉落仅对岛屿成员可见 |
| `ProgressEvents.java` | 4KB | **进度追踪** — 访问追踪、领导者变更迁移 |
| `ToolMenuEvents.java` | 4KB | **工具菜单** — 树苗右键菜单交互 |
| `WorldGuardEvents.java` | 3KB | **封禁玩家拦截** — 通过移动事件阻止被封禁玩家进入 |
| `InternalEvents.java` | 2KB | **内部事件** — 插件自定义事件（重启、创建岛屿、成员变动、分数变化） |
| `WitherTagEvents.java` | 2KB | **凋零标记** — 通过 PDC 标记凋零所属岛屿 |
| `MenuEvents.java` | 1KB | **菜单 GUI** — `SkyBlockMenu` 的库存点击处理 |

#### 事件监听器代码模式

```java
@Singleton
public class XxxEvents implements Listener {
    private final uSkyBlock plugin;

    @Inject
    public XxxEvents(@NotNull uSkyBlock plugin) {
        this.plugin = plugin;
        // 可选: 启动定时任务
        // plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickMethod, 20L, 1L);
    }

    @EventHandler(priority = EventPriority.X, ignoreCancelled = true)
    public void onSomeEvent(SomeEvent e) {
        // 1. 世界检查: plugin.getWorldManager().isSkyWorld() / isSkyNether() / isSkyAssociatedWorld()
        // 2. 权限/绕过检查 (usb.mod.bypassprotection)
        // 3. 岛屿归属验证: plugin.playerIsOnIsland() 或 WorldGuardHandler.getIslandNameAt()
        // 4. 业务逻辑
        // 5. 消息: I18nUtil.tr("message.key")，或按现状直接硬编码中文 "§c..."
    }
}
```

### 硬依赖与软依赖

**硬依赖:** WorldEdit ≥ 7.2.12（编译 7.2.19）、WorldGuard ≥ 7.0.8（编译 7.0.9）、FastAsyncWorldEdit ≥ 2.4.3（编译 2.13.0，服务器运行 2.15.3）、VaultUnlocked ≥ 2.10（服务器运行 2.20.2；编译依赖 `VaultUnlockedAPI:2.20`，代码走传统 `net.milkbowl.vault.*` API）、Multiverse-Core ≥ 5.7.0（MVPlugins 社区维护版，包名 `org.mvplugins.multiverse`，vavr 已 relocate 到 `org.mvplugins.multiverse.external.vavr`）

**软依赖:** ActionBarAPI、Multiverse-Inventories ≥ 5.3.4（MVPlugins 版，编译 5.3.4）、MVdWPlaceholderAPI、SignShop（`uSkyBlock-Core/libs/SignShop-5.2.2.jar`，`compileOnly` 本地文件依赖，仓库里没有的模块无法构建）

### 关键资源文件

- `uSkyBlock-Core/src/main/resources/config.yml` — 主配置文件（518 行，`version: 111`，含末地 `end:` 节）
- `uSkyBlock-Core/src/main/resources/plugin.yml` — 插件元数据、命令、权限
- `uSkyBlock-Core/src/main/resources/biomes.yml` — 生物群系配置
- `uSkyBlock-Core/src/main/resources/challenges.yml` — 挑战配置
- `uSkyBlock-Core/src/main/resources/levelConfig.yml` — 岛屿等级计算配置
- `uSkyBlock-Core/src/main/resources/schematics/` — 内置蓝图：`acidIsland.schem`、`acidIslandNether.schem`、`skySMP.schematic`、`spawn.schem`
- `uSkyBlock-Core/src/main/resources/us/talabrek/ultimateskyblock/i18n.properties` — 内置 i18n 导出

### 数据存储

- **玩家数据库:** `playerdb` 目录，支持 file/memory/bukkit 三种后端
- **岛屿数据:** WorldGuard 区域（`<islandName>island` / `<islandName>nether` / `<islandName>end`）+ 区域元数据 + `orphans.yml`
- **PDC (PersistentDataContainer):** 毒水系统使用**区块 PDC** 存储水体毒性（key 格式 `T<y+64>`）；凋零标记、Vault 刷新时间戳也使用 PDC
- 区域 id 约定：后缀 `island` / `nether` / `end`（`WorldGuardHandler.ISLAND_REGION_SUFFIXES = ["island","nether","end"]`，`EXTRA_WORLD_SUFFIXES = ["nether","end"]`）；一律走 `WorldGuardHandler.getIslandNameFromRegion()` 截取岛屿名，**不要再写 `substring(length - 6)` 之类的硬编码后缀长度**
- 区域版本：`WorldGuardHandler.VERSION`（当前 `15`）会写进每个岛的 `general.regionVersion`；**新增/改变某维度的区域时必须递增它**，老岛才会在 `protectIsland()`（岛主登录触发）或 `/usb protectall`（遍历所有岛）时重建 **island + nether + end 三套**区域。版本没变时 `protectIsland()` 会跳过，老岛不会自动补出新维度的区域（末地就是靠 14 → 15 让所有老岛补出 `end` 区域，`/usb protectall` 的 `N` = `islands/` 下当前岛屿文件数）

### 世界与传送核心机制（实现新维度前必读）

- `WorldManager` 持有 `static volatile skyBlockWorld / skyBlockNetherWorld / skyBlockEndWorld`，主世界名来自 `Settings.general_worldName`；下界名 = 主世界名 + `_nether`，末地名 = 主世界名 + `_the_end`（`getEndWorldName()`）
- `WorldManager.isSkyWorld()` / `isSkyNether()` / `isSkyEnd()` / `isSkyAssociatedWorld()` 是几乎所有监听器的世界判定入口，被 50+ 处调用（防破坏、边界保护、毒水、访客保护、方块上限、木牌等）。**`isSkyAssociatedWorld()` 现已纳入末地**：判定条件为「世界名以主世界名开头」，只在 `Settings.nether_enabled` / `Settings.end_enabled` 关闭时分别排除对应维度（历史上"显式排除 `THE_END`"的写法已经不存在）
- 下界维度模型：**不做 8:1 坐标缩放**，而是"同 X/Z、Y 取主世界高度的一半"，每个岛屿在下界有等价的独立区域；蓝图 `acidIslandNether.schem` 在创建岛屿时一并粘贴
- 下界区域由 `WorldGuardHandler.updateRegion()` 同步创建，Y 范围固定 `0..255`，区域 id = `<islandName>nether`
- 末地维度模型：同样是"每人一块独立区域"，但世界是**自定义虚空世界**（`AcidEndChunkGenerator`，无底部、单群系、`generateStructures(false)`），世界名 `<主世界>_the_end`、`Environment.THE_END`；区域 id = `<islandName>end`，Y 固定 `0..255`
- 末地到达点是每个岛屿自己那块 **5×5 黑曜石平台**：`EndPortalEvents` 改写 `setTo()` 后再由 `buildArrivalPlatform()` 重建平台并清空上方 3 格（生电装置依赖这个"每次进入都重建"的原版行为）
- 三个维度分别通过 `MultiverseHook.registerOverworld()` / `registerNetherworld()` / `registerEndWorld()` 导入 Multiverse，`linkInventories()` 把三者放进同一个 `skyblock` 组共享背包（避免切维度丢装备）
- 传送门拦截逻辑分散在 `ExploitEvents.onPortalEvent`（访客/他人岛屿限制）、`PlayerEvents.onPlayerPortal`（下界新手任务门槛 + 记 `enter_nether` 进度；创造/旁观放行且照样记进度，**不要**再用 `isOp()` 做提前 return，否则 op 账号永远记不到进度）、`PlayerEvents.onPlayerTeleportGate`（MONITOR 维度门槛，覆盖 `/tpa`/插件传送）、`EndPortalEvents.onPlayerPortalMonitor`（末地门通过全部校验后记录 `enter_end` 进度）、`EndPortalEvents`（`END_PORTAL` / `ENDER` 目的地改写、实体过门、末影珍珠跨区域）、`PlayerEvents.onEntityPortal`（实体门户）
- 岛屿删除：`IslandLogic.clearIsland()` 串联为 主世界 → 下界 → 末地 → `afterDeletion`

## 当前工作方向

### 分支 `everisland` 的既有改动

- 适配 Paper 26.2 API（减少 `dispatchCommand`，优先使用原生 API）
- **Multiverse 迁移到 MVPlugins v5：** 入口从 `PluginManager.getPlugin()` 改为 `MultiverseCoreApi.get()`，世界注册从 `addWorld()` 改为 `importWorld(ImportWorldOptions)`，vavr 类型 relocated
- **毒水系统 (ToxicEvents)**、**硫磺系统 (SulfurEvents)**、**祭坛系统 (AltarEvents)** 的持续迭代
- **试炼刷怪笼 (SpawnEvents)** 检测与转换
- **性能优化：** `preventAcidCreature` 提前判定以优化 `BlockLimitLogic`；镰刀掉落物位置调整

### 进行中：末地世界（The End）维度

目标：像下界一样，让每位玩家在末地拥有自己的区域并自由建造。**本服是生电（技术生存）玩法，末地必须支持刷沙机等依赖"实体穿过末地传送门 + 末地平台重建"机制的装置**，这约束了整个设计。

已确认的决策（完整表见 `END-DESIGN.md` §5）：①自定义虚空世界（无底部），每人一块区域；②**不处理末影龙/龙战**（vanilla 龙战运行，上线后管理员手动击杀原点的龙；击杀会生成原版祭坛/龙蛋）；③**所有实体都允许穿过末地传送门**（生电需求），到达点是**每个岛屿自己那块独立的 5×5 黑曜石平台**（原版机制、每岛一份，由 `setTo()` 落到该岛坐标）；④无岛屿玩家禁止进入末地；⑤末地参与岛屿等级计算；⑥末地纳入 `isSkyAssociatedWorld()` 并逐系统排除。

**已实现、不要在末地重复造的部分**：鞘翅与末地门框架都已通过 `uSkyBlock/challenges.yml` 的挑战在主世界发放（`ClassEnd` 章节给 `end_portal_frame`、幻翼膜挑战链给 `elytra`）。

- **设计方案、问题清单、决策表与实施状态见 [`END-DESIGN.md`](END-DESIGN.md)** — 动这部分代码前先读它；**§7 记录了已实现/未完成清单**（当前实现已通过 `buildPlugin` 编译，但尚未在服务器实测，且缺少 `acidIslandEnd.schem` 蓝图）
- **服务器实测步骤见 [`END-P0-CHECKLIST.md`](END-P0-CHECKLIST.md)** — 5 项 P0 验证（平台是否跟随 `setTo()`、落点高度、破坏范围、实体是否跟随、刷沙机链路），部署到测试服后照着走一遍
- 新增/改动的主要文件：`world/AcidEndChunkGenerator.java`（新）、`event/EndPortalEvents.java`（新）、`world/WorldManager.java`、`hook/world/MultiverseHook.java`、`handler/WorldGuardHandler.java`、`island/IslandGenerator.java`、`island/IslandLogic.java`、`island/LimitLogic.java`、`island/level/ChunkSnapshotLevelLogic.java`、`island/level/CommonLevelLogic.java`、`player/PlayerInfo.java`、`Settings.java`、`bootstrap/Listeners.java`、`bootstrap/Services.java`、`event/PlayerEvents.java`、`event/SpawnEvents.java`、`event/IslandBorderEvent.java`、`uSkyBlock.java`、`uSkyBlock-API/.../api/PlayerInfo.java`、`config.yml`（默认与运行时）

## 服务器运行配置与本地运维目录

工作区里有几个不参与插件构建的辅助目录，**除 `scripts/` 外都被 `.gitignore` 忽略**：

| 目录 | 用途 |
|------|------|
| `uSkyBlock/` | 服务器 `plugins/uSkyBlock/` 的配置快照 —— **配置真相源** |
| `acidisland-recipes/` | 服务器 `world/datapacks/acidisland-recipes/` 的数据包快照 |
| `scripts/` | 本地运维脚本（拉服务器日志），可以提交 |
| `server-logs/` | 从测试服拉回来的日志（本地排障用，不要提交） |

### `uSkyBlock/` — 插件运行配置（**配置真相源**）

服务器 `plugins/uSkyBlock/` 目录的完整快照，独立 git 仓库管理。⚠️ **插件实际读取的是这里的配置**；`uSkyBlock-Core/src/main/resources/` 里的同名文件只是打包默认值，**且已经过时**（缺少 `ClassEnd` / `enter_end` / `the_end` 群系等条目）。改配置一律以本目录为准。

| 文件/目录 | 用途 |
|-----------|------|
| `config.yml` | 插件运行时主配置（改动默认配置时需同步这里） |
| `biomes.yml` | 运行时群系配置（含主世界岛屿群系覆盖，如 `the_end`） |
| `challenges.yml` | 运行时挑战配置（**包含大量仓库内没有的定制挑战**：`ClassEnd` 末地之试炼章节发放 `end_portal_frame`、`phantom`/`phantom_100` 发放 `elytra`、`enter_end` 等） |
| `levelConfig.yml` | 运行时岛屿等级计算配置 |
| `i18n/zh_CN.po` | 中文本地化文件 |
| `schematics/` | 岛屿蓝图：`acidIsland.schem`、`acidIslandNether.schem`、`skySMP.schematic`、`spawn.schem`（末地蓝图 `acidIslandEnd` 待新增） |

> 说明：`enter_end` 挑战在 `uSkyBlock/challenges.yml` 定义，触发点在 **`EndPortalEvents.onPlayerPortalMonitor`**（MONITOR + ignoreCancelled，注册晚于 `PlayerEvents` 的维度门槛）：只有经过插件目的地校验、他人岛屿拦截、`advanced` 维度门槛后仍然有效的末地门传送才记录进度，所以不会出现旧实现那种“无岛 / 未链接 / 他人岛屿 / 大厅末地”误完成。**不要**把它挪回 `onPlayerPortal`（HIGHEST），那里事件还能被后续插件取消。

> **事件触发类挑战的统一写法（`type: progress` + 单次触发）**：`challenges.yml` 里这类挑战（`enter_nether` / `enter_end` / `build_altar` / `biome_frozen` / `biome_warm` / `biome_nether` / `biome_nether_all` / `anvilleaves` / `armor_protection` / `conduit_protection`）都写成 `type: progress` + `requiredProgress: <与挑战同名的键>:N`，**不要**再用 `type: onPlayer` + `requiredItems: bedrock:1` 那种占位写法（基岩不可得，只是让玩家点不动，而且绕过发奖逻辑）。触发点只调 `ChallengeLogic.markProgress(player|location, key)`（location 版自动归到岛主条目，供没有 Player 的定时任务使用），进度满后玩家在 `/c` 里点击领取，奖励统一走 `giveReward`。一个挑战可以带多条 `requiredProgress`（`biome_nether_all` 就是要求 5 种下界群系各 1 次，键名 `nether_biome_<群系>`）。
>
> 挑战描述可以在 `challenges.yml` 里用 `\n` 强制换行：`Challenge.getDescriptionLines()` 会按行展开成多条 lore（`FormatUtil.wordWrap` 只按空格断行，中文描述不换行会变成超长的一行）；聊天提示和木牌那种不能换行的地方用 `getDescriptionOnOneLine()`。

### `acidisland-recipes/` — 自定义数据包

服务器 `world/datapacks/acidisland-recipes/` 的完整快照，提供自定义配方和附魔：

- **`data/acidisland/recipe/`** (约 40 个配方) — 矿石类、下界资源类、珊瑚块、特殊方块、试炼相关（`trial_spawner.json`、`vault.json`、`ominous_vault.json`）、辅助配方（解毒、黑色染料）
- **`data/acidwater/enchantment/`** — 自定义附魔：`acid.json`（酸蚀）、`anti_acid.json`（抗酸）
- **`data/minecraft/tags/enchantment/curse.json`** — 诅咒附魔标签
- **`pack.mcmeta`** — 数据包元数据

### `scripts/` — 本地运维脚本

只用 **Windows PowerShell 5.1**（本机没有 `pwsh`）。脚本必须存成 **UTF-8 with BOM**，否则 5.1 会按 ANSI 解码，中文乱码并直接解析失败。调用方式：`powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\xxx.ps1`，或直接 `.\scripts\xxx.ps1`。

| 脚本 | 用途 |
|------|------|
| `fetch-log.ps1` | 拉取测试服日志到 `server-logs/`，打印大小、远程修改时间、总行数与 ERROR/WARN/异常堆栈计数 |

```powershell
.\scripts\fetch-log.ps1                        # 拉 logs/latest.log（覆盖 + 时间戳副本）
.\scripts\fetch-log.ps1 -Tail 200              # 拉完只看最后 200 行
.\scripts\fetch-log.ps1 -ErrorsOnly            # 只打印 ERROR / Exception / Caused by 行
.\scripts\fetch-log.ps1 -Grep 'uSkyBlock'      # 自定义正则过滤
.\scripts\fetch-log.ps1 -SinceRestart          # 只保留本次开服以来的日志，另存 --boot.log
.\scripts\fetch-log.ps1 -List                  # 只列远程 logs/（含 .gz 轮转日志）
.\scripts\fetch-log.ps1 -Remote 2026-08-18-2.log.gz   # 抓某个旧日志
```

默认连接参数写在脚本 param 里：`gj999@gw.evermc.net`（**SSH 密钥认证已可用**，脚本带 `BatchMode=yes`，密钥失效时立即报错而不是卡在密码提示）、远程日志目录 `/nfs/3406e49acd/minecraft/acidisland-test/logs`。可用 `-User` / `-SshHost` / `-Port` / `-RemoteLogDir` / `-OutDir` 覆盖。

> **不要写本地自动上传/部署脚本。** 测试服的插件更新走 **GitHub 仓库 hook**（push 后服务器自己拉取并替换 jar，日志里能看到 `[Rcon] 开始更新空岛主插件配置（commit: ...）`），服务器重启由人工完成。也就是说 **push 就等于部署**，所以推送前必须先拿到用户明确要求，详见上文「权限与推送约定」。另外 `gj999` 对 `plugins/` 目录只有读权限（`drwxr-sr-x kjiang:minecraft`，同组其他目录才是 2775），本来也覆盖不了 `uSkyBlock-Plugin.jar`。

### `server-logs/` — 拉回来的服务器日志

`fetch-log.ps1` 的输出目录（默认仓库根下 `server-logs/`，已加入 `.gitignore`）：

- `latest.log` —— 每次覆盖；用 `-Remote` 抓别的文件时按原文件名保存
- `latest-<yyyyMMdd-HHmmss>.log` —— 带时间戳的历史副本（`-NoHistory` 可关）
- `latest-<yyyyMMdd-HHmmss>--boot.log` —— `-SinceRestart` 时截出的"本次开服以来"片段

纯本地排障数据，不要提交；`.gz` 轮转日志原样下载，不解压。

## 注意事项

- **权限边界：** 会话开「完全权限」只是为了 git / gradlew 能跑通；工作区以外的写入仍须先征得用户同意（见「权限与推送约定」）
- **默认不推送：** 插件仓库和配置文件仓库一 push 就会跑流水线、测试服立刻更新 —— 除非用户明确要求，不要 `git push`
- 本地依赖 jar 放在 **`uSkyBlock-Core/libs/`**（不是仓库根），例如 `SignShop-5.2.2.jar`；`uSkyBlock-Core/build.gradle.kts` 用 `compileOnly(files("libs/SignShop-5.2.2.jar"))` 引用它，缺这个文件核心模块编不过
- 配置文件版本号通过 `config.yml` 的 `version` 字段管理（当前 **111**，末地功能把它从 110 提到 111）；新增配置项时注意同时更新 `uSkyBlock/config.yml` 快照
- 国际化使用 gettext `.po` 格式，工具类为 `dk.lockfuglsang.minecraft.po.I18nUtil`；但**部分新增功能直接硬编码中文提示**（如 `PlayerEvents.onPlayerPortal` 的下界门槛提示），改文案时两处都要找
- 插件内定时任务使用 `BukkitRunnable` 或 `Scheduler.runTaskTimer()`；跨模块异步任务注意回到主线程
- 岛屿世界判定统一走 `WorldManager`，**不要**在监听器里直接比较世界名
- Multiverse-Core v5 API 入口为 `MultiverseCoreApi.get()`（`org.mvplugins.multiverse.core`），世界操作用 `WorldManager.importWorld(ImportWorldOptions)` / `getWorld(name).getOrNull()`；vavr 类型在 `org.mvplugins.multiverse.external.vavr` 包下
- WorldEdit/FAWE 区域操作通过 `WorldEditHandler` / `AsyncWorldEditHandler` 包装，蓝图粘贴是**异步**的（回调完成后再做后续操作，参考 `IslandLogic.clearIsland`）
