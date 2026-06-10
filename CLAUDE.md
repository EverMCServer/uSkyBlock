# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此仓库中工作时提供指导。

## 项目概况

这是在开源插件 [uSkyBlock](https://github.com/rlf/uSkyBlock) 基础上深度定制的 Minecraft 海岛生存服务端插件，运行于 **Paper 1.21.11**（`everisland` 分支）。项目在原始空岛玩法上增加了大量扩展特性：毒水系统、祭坛系统、下界改造、试炼刷怪笼转换、实体跨边界保护等。

- **原始仓库:** `https://github.com/rlf/uSkyBlock`
- **组 ID:** `ovh.uskyblock`
- **版本:** `3.2.0-SNAPSHOT`
- **主包名:** `us.talabrek.ultimateskyblock`
- **许可证:** GPLv3

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

**注意:** 根 `build.gradle.kts` 中 `tasks.withType<Test>().configureEach { enabled = false }` 默认禁用了测试，测试仅在 `:uSkyBlock-Core` 子模块中显式配置（JUnit 4 + Mockito + Hamcrest）。

## 项目架构

### 多模块结构

| 模块 | 用途 |
|------|------|
| `uSkyBlock-Core` | **核心模块**，包含所有业务逻辑、事件监听、岛屿管理、GUI、命令 |
| `uSkyBlock-API` | 旧版公开 API（v1），第三方插件通过此 API 获取岛屿等级/排名等信息 |
| `uSkyBlock-APIv2` | 新版 API（v2），推荐外部使用 `UltimateSkyblockProvider.get()` 获取 |
| `uSkyBlock-Plugin` | **装配模块**，通过 ShadowJar 将所有模块打包为可部署的 `uSkyBlock.jar` |
| `uSkyBlock-FAWE` | FastAsyncWorldEdit 集成适配器 |
| `bukkit-utils` | 通用 Bukkit 工具库（命令框架、动画、文件/YAML 工具） |
| `po-utils` | gettext `.po` 文件解析和国际化工具（`I18nUtil`） |

### 依赖注入架构

核心模块使用 **Google Guice** 进行依赖注入：

- **`bootstrap/SkyblockModule`** — Guice 模块，绑定所有主要服务（`uSkyBlock`、`Plugin`、`LevelLogic`、`PlayerDB` 等）
- **`bootstrap/SkyblockApp`** — 顶层编排器，注入 `Services`、`Commands`、`Listeners`
- **`bootstrap/Services`** — 生命周期管理：`startup()` → `delayedEnable()` → `shutdown()`
- **`bootstrap/Listeners`** — 根据 `Settings` 配置条件注册所有 15+ 个事件监听器
- **`bootstrap/Commands`** — 注册命令

事件监听器均标注 `@Singleton`，通过构造函数注入 `uSkyBlock plugin` 和其他依赖。

### 核心包结构 (`uSkyBlock-Core/src/main/java/us/talabrek/ultimateskyblock/`)

| 包 | 职责 |
|----|------|
| `event/` | **Bukkit 事件监听器（15 个文件）** — 主要修改区域 |
| `island/` | 岛屿核心逻辑：`IslandInfo`、`IslandLogic`、`BlockLimitLogic`、`LimitLogic`、`OrphanLogic`、`island/level/`、`island/task/` |
| `player/` | 玩家管理：`PlayerInfo`、`PlayerLogic`、`PerkLogic`、`TeleportLogic` |
| `challenge/` | 挑战/任务系统 |
| `command/` | 命令框架和具体命令实现 |
| `handler/` | 外部插件集成（WorldGuard、WorldEdit、AsyncWorldEdit、PlaceholderAPI） |
| `hook/` | 经济系统等外部插件 Hook |
| `world/` | 世界管理（`WorldManager`、`AcidBiomeProvider`） |
| `bootstrap/` | Guice DI 启动配置 |
| `gui/` | GUI 菜单系统 |
| `util/` | 工具类（`LocationUtil`、`Scheduler`、`TranslationUtil` 等） |
| `progress/` | 玩家进度追踪系统 |
| `uuid/` | 玩家 UUID/名称数据库（文件、内存、Bukkit 三种实现） |

### 事件目录 (`event/`)

这是主要的修改区域，共 15 个事件监听器文件：

| 文件 | 大小 | 功能 |
|------|------|------|
| `AltarEvents.java` | 71KB | **祭坛系统** — 5 种祭坛（收获、战争、财富、灵魂、探索），Buff、附魔机制 |
| `PlayerEvents.java` | 53KB | **玩家交互** — 考古可疑方块转化、试炼刷怪笼逻辑等 |
| `IslandBorderEvent.java` | 21KB | **跨边界实体保护** — 防止物品/生物/载具穿越岛屿边界 |
| `SpawnEvents.java` | 17KB | **刷怪管理** — 刷怪限制、生物生成控制、试炼刷怪笼转换 |
| `ToxicEvents.java` | 17KB | **毒水系统** — 酸性水扩散和伤害机制（使用 PDC 存储数据） |
| `NetherTerraFormEvents.java` | 12KB | **下界改造** — 玩家交互时方块转换 |
| `GriefEvents.java` | 9KB | **防破坏** — 苦力怕、凋零、践踏、剪羊毛、孵化等防护 |
| `ExploitEvents.java` | 6KB | **防漏洞利用** — 传送门滥用、村民交易、他人岛屿骑车 |
| `ItemDropEvents.java` | 5KB | **物品掉落保护** — 限制物品掉落仅对岛屿成员可见 |
| `ProgressEvents.java` | 5KB | **进度追踪** — 访问追踪、领导者变更迁移 |
| `ToolMenuEvents.java` | 5KB | **工具菜单** — 树苗右键菜单交互 |
| `WorldGuardEvents.java` | 3KB | **封禁玩家拦截** — 通过移动事件阻止被封禁玩家进入 |
| `InternalEvents.java` | 2KB | **内部事件** — 插件自定义事件（重启、创建岛屿、成员变动、分数变化） |
| `WitherTagEvents.java` | 2KB | **凋零标记** — 通过 PDC 标记凋零所属岛屿 |
| `MenuEvents.java` | 1KB | **菜单 GUI** — `SkyBlockMenu` 的库存点击处理 |

#### 事件监听器代码模式

所有事件类遵循统一模式：
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
        // 1. 世界检查: plugin.getWorldManager().isSkyWorld() 或 isSkyAssociatedWorld()
        // 2. 权限/绕过检查
        // 3. 岛屿归属验证: plugin.playerIsOnIsland() 或 WorldGuardHandler.getIslandNameAt()
        // 4. 业务逻辑
        // 5. 国际化消息: I18nUtil.tr("message.key")
    }
}
```

### 硬依赖与软依赖

**硬依赖:** Vault ≥ 1.7.1、WorldEdit ≥ 7.2.12、WorldGuard ≥ 7.0.8、FastAsyncWorldEdit ≥ 2.4.3、Multiverse-Core ≥ 4.3.1

**软依赖:** ActionBarAPI、Multiverse-Inventories、MVdWPlaceholderAPI、SignShop

### 关键资源文件

- `uSkyBlock-Core/src/main/resources/config.yml` — 主配置文件（480+ 行）
- `uSkyBlock-Core/src/main/resources/plugin.yml` — 插件元数据、命令、权限
- `uSkyBlock-Core/src/main/resources/biomes.yml` — 生物群系配置
- `uSkyBlock-Core/src/main/resources/challenges.yml` — 挑战配置
- `uSkyBlock-Core/src/main/resources/levelConfig.yml` — 岛屿等级计算配置

### 数据存储

- **玩家数据库:** `playerdb` 目录，支持 file/memory/bukkit 三种后端
- **岛屿数据:** 通过 WorldGuard 区域 + 元数据存储
- **PDC (PersistentDataContainer):** 毒水系统使用区块 PDC 存储水体毒性数据（key 格式: `T<y+64>`），凋零标记也使用 PDC

## 当前分支 `everisland` 的改动方向

从最近提交记录可知，当前分支专注于：
- 适配 Paper 1.21.11 API（减少 `dispatchCommand`，优先使用原生 API）
- **毒水系统 (ToxicEvents):** 酸性水体的扩散、伤害、使用 PDC 持久化，重置岛屿时清除 PDC 记录
- **试炼刷怪笼 (SpawnEvents):** 试炼刷怪笼的检测和转换逻辑
- **祭坛系统 (AltarEvents):** 收获祭坛提示优化
- **性能:** `preventAcidCreature` 优先判定以优化 `BlockLimitLogic` 性能
- 镰刀掉落物位置调整

## 注意事项

- 项目根目录有 `libs/` 目录存放本地依赖（如 `SignShop-5.0.0-dev.jar`）
- 配置文件版本号通过 `config.yml` 中的 `version` 字段管理（当前 110）
- 国际化使用 gettext `.po` 格式，工具类为 `dk.lockfuglsang.minecraft.po.I18nUtil`
- 插件内定时任务使用 `BukkitRunnable` 或 `Scheduler.runTaskTimer()`
- 岛屿世界由 `WorldManager.isSkyWorld()` / `isSkyAssociatedWorld()` 判断
- Java 21 编译目标，使用 Kotlin 2.1.10 作为 Gradle 脚本语言
