# 末地世界（The End）设计方案

> 状态：**待评审**。本文档只负责"问题清单 / 技术路线 / 决策点 / 实施计划"，不含实现代码。
> 相关背景见 [`AGENTS.md`](AGENTS.md)；末地的实现应对齐现有的**下界模型**。

---

## 1. 目标与结论速览

**目标**：像下界一样，每位玩家在末地拥有独立区域，可在自己的区域内自由建造；末地成为中后期资源与建造空间（末地石、紫颂、末影珍珠、潜影盒等）。

**当前技术路线（推荐方案，待确认）**：

| 方面 | 方案 |
|------|------|
| 世界 | 新增 `<worldName>_the_end`，`Environment.THE_END`，**自定义空岛生成器**（虚空，无底部），生成结构与自然群系全部关闭 |
| 布局 | **同 X/Z**，Y 用独立的 `end.height`（不复用下界的 `y/2`），每位玩家的区域与主世界岛屿一一对应 |
| 保护 | WorldGuard 区域 `<islandName>end`，Y 范围 `0..255`，与 `<islandName>nether` 完全对称 |
| 进入 | `PlayerPortalEvent` / `EntityPortalEvent` 改写目的地；**到达点是每个岛屿各自独立的 5×5 黑曜石平台**（原版机制，但每岛一份），坐标 = 该岛 X/Z + `end.height` |
| 返回 | 每个岛屿在末地的区域内用**蓝图**建造返回传送门（与到达平台保持距离），回到自己主世界岛屿 |
| 末影龙 | **不生成**（决策 D2=A）；末影龙战斗/龙蛋计划用 MythicDungeon 做独立副本，不属于本次范围 |
| 鞘翅 / 末地门框架 | **已通过 challenge 实现在主世界发放**（运行时 `uSkyBlock/challenges.yml`），末地侧不需要再做获取途径 |
| 实体过门 | **全部实体都允许通过**（生电需求，决策 D4=放开）|
| 刷沙机 | **必须能实现**（生电玩法），依赖原版"实体进门即重建末地平台"机制，见 §4.3 |
| 无岛屿玩家 | **拒绝**进入末地（决策 D6=A） |
| 岛屿等级 | 末地计分，参考下界做法（决策 D7=B，需 `end.activate-at.level` 门槛） |
| 世界判定 | 末地全局纳入 `isSkyAssociatedWorld()`，再逐系统排除（决策 D11=A） |

**已确认的决策**：D1=A / D2=A（龙另做副本）/ D2b、D5=已由 challenge 实现 / D4=放开所有实体 / D6=A / D7=B / D8=A / D9=A / D10=A / D11=A / D12=**每岛各自独立的到达黑曜石平台** + 蓝图返回门 / D13=先做人在场 / D14=仅兜底点清理。完整决策表见 §5。

**⚠️ 配置真相源**：本插件读取的是 **`uSkyBlock/` 目录（服务器 `plugins/uSkyBlock/`）的运行时配置**，仓库内 `uSkyBlock-Core/src/main/resources/` 里的是**打包用默认值、已经过时**（缺少 `ClassEnd`/`enter_end`/`the_end` 群系等）。改配置一律以 `uSkyBlock/` 为准。

---

## 2. 现有下界实现基线

实现末地时必须先理解这套现成机制，末地是它的镜像：

| 环节 | 下界现状 | 代码位置 |
|------|----------|----------|
| 世界创建 | `getNetherWorld()`：`WorldCreator` + `Environment.NETHER` + `Type.NORMAL` + `generateStructures(false)` + 自定义生成器；世界名 `worldName + "_nether"` | `world/WorldManager.java:260` |
| 生成器 | `AcidNetherChunkGenerator`：y=0 与 y=127 基岩、y=1..31 岩浆、y=128 蘑菇装饰、固定单群系、`getFixedSpawnLocation` | `world/AcidNetherChunkGenerator.java` |
| 世界判定 | `isSkyWorld()` / `isSkyNether()` / `isSkyAssociatedWorld()`（**显式排除 `THE_END`**） | `world/WorldManager.java:299-336` |
| 蓝图 | 建岛时同时粘贴 `acidIslandNether.schem`，Y = `Settings.nether_height`（默认 `island_height/2` = 35） | `island/IslandGenerator.java:96` |
| 区域 | `updateRegion()` 同步创建 `<name>island` 与 `<name>nether`（Y 固定 0..255） | `handler/WorldGuardHandler.java:93` |
| 坐标换算 | **不做 8:1 缩放**，而是"同 X/Z + Y 折半" | `player/PlayerInfo.java:142`、`island/IslandLogic.java:200`、`island/level/CommonLevelLogic.java:33` |
| Multiverse | `registerNetherworld()` 导入 + Multiverse-Inventories 的 `skyblock` 组共享背包 | `hook/world/MultiverseHook.java:112` |
| 岛屿等级 | 下界区块快照参与等级计算，且有 `nether.activate-at.level` 门槛 | `island/level/ChunkSnapshotLevelLogic.java`、`AweLevelLogic.java` |
| 生态环境 | `NetherTerraFormEvents`：挖掘转化 + 猪灵替换成凋灵骷髅/烈焰人 | `event/NetherTerraFormEvents.java` |
| 删除岛屿 | `clearIsland()` 先清主世界，回调里清下界 | `island/IslandLogic.java:167` |
| 清理任务 | 区域删除时 `removeRegion(name + "nether")` | `handler/WorldGuardHandler.java:275` |

> 注意：`NetherTerraFormEvents` 通过 `Listeners` 按 `Settings.nether_enabled` 条件注册（`bootstrap/Listeners.java:123`），末地监听器应照抄这个模式。

---

## 3. 末地与下界的关键差异（决定了不能简单复制粘贴）

这是本次工作的核心难点，**差异点即风险点**：

| # | 差异 | 后果 |
|---|------|------|
| D-1 | 下界传送门按坐标缩放链接；**末地传送门的目标点是"末地世界出生点"这一固定点，与来源坐标无关**（Bukkit 文档原文：`END_PORTAL` 的 `getTo()` "will initially be the exact destination … end spawn for any world→end"） | 所有岛屿的玩家默认都挤在末地出生点 → **必须逐个改写目的地**，不能靠坐标自然分离 |
| D-2 | 末地每个世界只有**一条**末影龙，且默认**不会重生**（需末地水晶复活仪式） | "每人一块区域"与"世界 Boss"天然冲突 → 必须做取舍（§4.2） |
| D-3 | 实体在末地没有"目标平台"概念前会**掉进虚空**；末地也没有床/重生锚可设重生点 | 落地保护、返回路径必须自己做（§4.5、§4.6） |
| D-4 | 末地平台（End Platform）是 **5×5 黑曜石**，**每次任何实体进入末地都会重建**；重建时**平台正上方 3 格内的方块按"被玩家挖掘"处理并掉落成物品**（1.21 起）；API 文档明确 "end portal target platforms … always appear at the target location"，**不受 `setCanCreatePortal`/`setCreationRadius` 控制** | 玩家/实体进入末地必然改写到达点的方块 → **每个岛的到达平台都要专门规划**（平台上方 3 格留空），否则会破坏玩家建筑（§4.3） |
| D-5 | 末影人 + 末地石 + 紫颂果的经济学与下界完全不同（下界的关键产物是石英/烈焰棒/远古残骸，靠挖掘转化可循环） | 末地石/紫颂是否可再生需要单独设计（§4.10） |
| D-6 | `isSkyAssociatedWorld()` 目前把末地排除在外，而它有 **50+ 个调用点** | 是否把末地纳入"设施世界"，需要在每个子系统上逐一决策（§4.12） |
| D-7 | 末地没有昼夜与天气、天空光照特殊、无下界岩层结构可参照 | 生成器、幻翼/刷怪逻辑要复核（§4.8） |
| D-8 | 末地是**出口式**维度：实体进入后到达"世界出生点"，而不是"按坐标缩放后的对应点" | 任何实体（含沙子/掉落物）都必须被显式改写目的地点，否则全部汇聚到同一个点（§4.3） |

---

## 4. 问题清单、方案与推荐

> 标注说明：**【用户已问】** = 你提出的问题；**【补充】** = 你没提到但必须一起决策的问题。

### 4.1 【用户已问】怎样重定向末地传送门

**可用 API（已核对 Paper 26.x Javadoc）**

- 玩家：`PlayerPortalEvent`（继承 `PlayerTeleportEvent`，`cause = END_PORTAL`）
  - `getTo()`：末地目标场景下**就是精确目的地**（世界出生点），可直接 `setTo(Location)` 改写
  - `setSearchRadius / setCanCreatePortal / setCreationRadius`：对末地**不适用**（只影响下界那类"找/造传送门"），别指望用它们
- 非玩家实体：`EntityPortalEvent`（有 `getPortalType() == PortalType.ENDER`），`getTo()` 语义同上，同样可改写；`setTo(null)` 等价于取消

**推荐路线**：在专用监听器（建议 `event/EndTerraFormEvents.java` 或拆为 `EndPortalEvents.java`）里统一处理：

1. `PlayerPortalEvent`，`cause == END_PORTAL`
   - 来源在世界 A（主世界岛屿）→ 目标是末地：先判定 `WorldGuardHandler.getIslandNameAt(event.getFrom())`
     - 命中 `<name>island` → `event.setTo(该岛屿在末地的到达点)`（X/Z 同岛屿，Y = `end.height`）
     - 未命中（主世界出生点/公共区域等）→ `setTo(末地兜底点)`（远离所有岛屿区域的安全平台，带返回门）
     - 玩家没有岛屿 → **拒绝**（决策 D6=A）并提示
   - 来源是末地 → 目标是主世界：按末地侧区域名（`<name>end`）反查岛屿 → `setTo(该岛屿主世界到达点)`；无归属（兜底点）→ 主世界出生点
2. `EntityPortalEvent`，`portalType == ENDER`：**不取消**（决策 D4=放开，生电需求），但同样必须**改写目的地点**（见 §4.3），使刷沙机等装置落在**本岛自己的到达平台**上
3. **不要在 `PlayerEvents.onPlayerPortal` 之外另起一套逻辑**：现有 `PlayerEvents.onPlayerPortal`（`PlayerEvents.java:691`）已经在做"新手任务门槛 + 记录 `enter_nether` 挑战"，末地门槛应挂在那套逻辑上，避免两个监听器互相覆盖 `setTo()`。建议把末地/实体改写放在 `EventPriority.HIGHEST`（或 `MONITOR`），并明确与既有 `ExploitEvents.onPortalEvent`（MONITOR，做"他人岛屿传送门"限制）的优先级关系，避免互相覆盖。
4. **实体改写不能依赖区域查询失败时的兜底**：`EntityPortalEvent` 里的实体可能在未加载区域（例如掉落物在出生点附近、或区块刚加载）。建议 `getTo()` 只做一次 `WorldGuardHandler.getIslandNameAt(from)`；查不到就送到**末地兜底点**，**绝不**默认送到世界出生点（否则装置会跑到别处，见 §4.3）。

**必须同时处理的边界**：跨维度传送会触发 `PlayerTeleportEvent`；`PlayerEvents.onTeleport`（`PlayerEvents.java:655`）里已有封禁/上锁岛屿拦截，末地目标也要走同一套校验，否则会出现"被封禁玩家绕过岛屿锁定、直接落到对方末地区域"。

### 4.2 【用户已问】怎样处理末影龙 —— **已决策：D2=A（不生成龙）**

**约束事实**：一个世界一条龙；龙死后默认不再生（除非玩家用 4 个末地水晶做复活仪式）；末地折跃门（gateway）只在**击杀龙之后**生成；龙被击杀时在世界出生点的返回传送门上方生成龙蛋；龙死亡掉落大量经验。

**三种可选方案（保留备查，已选 A）**

| 方案 | 做法 | 优点 | 代价 |
|------|------|------|------|
| **A. 不生成龙（已选）** | 世界创建后立即移除龙实体；拦截复活仪式（末地水晶）；拦截末地水晶爆炸（顺手保护龙蛋/祭坛） | 结构最简单、无世界级竞争、不会出现"别人杀了龙我永远拿不到龙蛋"的不公平；末地变成纯粹的建造/资源维度 | 没有原版终局体验；**鞘翅/龙蛋/龙头/折跃门全部失去原生来源**，必须另配获取途径 |
| B. 龙归第一人 | 保留龙，全服共享一次击杀 | 保留一次原版终局体验 | 只有一个人能体验；龙死后末地永久空荡；龙蛋/折跃门归属问题；且需要"谁的第一次进末地"这类规则解释 |
| C. 每人一条龙（伪实例化） | 仅当某玩家的岛屿区域内有玩家时生成龙，用 PDC/记分板记录每个岛屿的龙状态；击杀奖励按岛屿发放 | 保留 Boss 体验且公平 | **一个世界只有一个 `DragonBattle`**，血量/阶段/水晶/退出传送门都是世界级的，需要大量 hack（伪造血条、独立判定、屏蔽原版结算），维护成本高、易随服务端升级失效；收益主要是"体验"而非玩法必需 |

**A 方案下必须一并处理的事（已明确，无需再决策）**：

- **鞘翅已在主世界可获得**：运行时 `uSkyBlock/challenges.yml` 中已有幻翼膜挑战链（`phantom` 2% 概率鞘翅、`phantom_100` 稳定给鞘翅），**末地侧不需要再提供获取途径**
- **末地传送门框架也已通过 challenge 发放**：运行时 `uSkyBlock/challenges.yml` 的 `ClassEnd`（末地之试炼）章节下，`dirt_trial` / `sand_trial` / `ice_trial` / `eye_trial` 等挑战奖励 `end_portal_frame:1`；玩家另需自备 **12 颗末影之眼**自行激活（`eye_trial` 也要求上缴末影之眼，说明该资源链已闭环）。**这意味着 D5 无需实现**
- **末影龙战斗 / 龙蛋**：计划用 **MythicDungeon 做挑战副本**，**不在本次末地世界范围内**。因此本次只需保证"世界出生点/主岛不存在一条野生的龙"
- **末地水晶**：仍建议禁止在主岛放置/引爆（否则会触发原版复活仪式，与"不做龙"冲突），并在 `GriefEvents` 类似位置拦截爆炸破坏
- **折跃门**：虚空世界不生成；外岛内容统一走未来副本，不做
- **经验/龙蛋**：不做，交由副本承担

**实现要点**：优先在**世界创建后同步移除**（`world.getEntitiesByClass(EnderDragon.class)`），并加一个低频巡检兜底；同时拦截末地水晶的放置/引爆。不要在第一个玩家进末地时才处理（可能已被看见/互动）。

> **bossbar 坑（已实测）**：末影龙 bossbar 属于世界的 `DragonBattle`（`world.getEnderDragonBattle().getBossBar()`），**不是**龙实体的附属物；用 `Entity#remove()` 移除龙不会移除 bossbar，它会一直显示（重新进末地还会被重新加回）。修复：`WorldManager.clearDragonBossBar()` 对 `DragonBattle` 的 bossbar 调 `setVisible(false)` + `removeAll()`，并在每次有人进入末地时立即清、每 20 tick 兜底；同时用 `EntitySpawnEvent` 取消末地世界的末影龙生成。

### 4.3 【用户已问】实体穿过末地传送门的行为 —— **已决策：D4=放开所有实体（生电玩法）**

> 这是本设计里**约束最强**的一条：因为要支持刷沙机（生电），我们不能用"禁止实体过门"这个最省事的做法，必须正面设计**每个岛自己的到达平台**。

#### 4.3.1 原版机制（已核对 Minecraft Wiki，务必按这个事实设计）

- **触发条件**：实体进入**末地传送门方块**（不是"玩家走进传送门"）。掉落物、箭、船、矿车、生物、TNT、**下落的沙子/沙砾（下落方块实体）**都会触发 `EntityPortalEvent`
- **末地平台（End Platform）**：**5×5 黑曜石**，位置固定在原版末地的 (100, 48, 0)；**任何实体每次进入末地都会重建它**
- **重建时会破坏平台正上方（y+1..y+3）3 格内的所有方块**，且 1.21 起这些方块是**按"被玩家挖掘"处理并掉落成物品**（容器会连内容物一起掉）
- **Java 版**：非玩家实体**同样会**触发平台重建（15w33c 曾移除，1.16 的 20w21a 又加回，至今有效）；**基岩版**不会
- **进入时的落点**：玩家落在平台中心上方（原版 x=100, y=49, z=0，1.16.2 起居中为 100.5）；**其他实体落在平台中心上方 1 格**（原版 y=50）
- **1.20.5 起**：实体到达末地后，平台周围区块会**保持加载 15 秒**（对齐下界传送门行为）→ 这对"装置依靠加载"的场景是利好
- **世界出生点**：原版末地的世界出生点就在平台处；**我们的实现把世界出生点设在原点 (0, `end.height`, 0)**，即全服共用的末地"主城"（见 §4.9），它同时承担兜底点。由于每岛都有自己的到达平台，正常路径下不会有人落到世界出生点；它只承担"目的地点改写失败/管理员传送"的兜底 —— 但**实体也必须改写目的地**，否则沙子/掉落物会全部堆到主城

**刷沙机为什么依赖上述机制（我们的理解，需在 P0 实机验证）**：把下落方块实体送进末地传送门后，实体在末地被"落地变回方块"，而主世界那一份沙子/沙砾仍然成立，于是产生复制；同时"到达点上方 3 格被破坏并掉落"这一条，是同类装置（以及"吞掉建筑"的风险）的根因。**装置本身不需要服务端配合，服务端必须做的是"让到达点落在本岛、位置固定、不破坏玩家建筑"**。

#### 4.3.2 推荐实现（到达点 = **每个岛屿自己那块独立的**黑曜石平台）

> **已决策 D12（口径已澄清）**：每个玩家岛屿在末地都有**自己的、独立的**到达黑曜石平台 —— 即"原版的 5×5 黑曜石平台机制，但每个岛一份"，**不是**全世界共用一个公共平台。平台由插件按该岛自己的 X/Z 铺设（`setTo()` 只决定实体落点，原版平台不跟随落点）；岛屿蓝图 `acidIslandEnd.schem` 负责在其附近建造**返回传送门**。
>
> ⚠️ **已核实的服务端事实（与最初假设不同）**：Paper 的 `EndPortalBlock#getPortalDestination` 是**先按原版 `ServerLevel.END_SPAWN_POINT` 建平台，再触发 `PlayerPortalEvent`/`EntityPortalEvent`**；事件里改 `to` 只改实体落点，**平台不会跟着 `setTo` 移动**。因此每岛平台必须由插件自己铺（`EndPortalEvents.buildArrivalPlatform`），并且重建时按"被玩家挖掘"掉落方块（含容器内容物）。P0 第 1 项若要验证原版行为，必须先临时关掉插件自己的平台重建，否则测到的是我们铺的平台。

1. **不改写 = 出事故**：所有实体（`EntityPortalEvent`，`PortalType.ENDER`）都必须改写为 `setTo(本岛末地到达点)`，否则所有岛的玩家/实体都会汇聚到同一个世界出生点
2. **到达点坐标约定**：
   - 玩家：`(islandX + 0.5, end.height, islandZ + 0.5)`，面朝西（原版玩家居中落在平台上）
   - 非玩家实体：`(islandX + 0.5, end.height + 1, islandZ + 0.5)`（与玩家同 X/Z，**高 1 格**，对应原版"实体比玩家高 1 格"的落点差）
   - 平台本体：中心 `(islandX, end.height - 1, islandZ)`，5×5 黑曜石；玩家脚在 `end.height`
3. **平台正上方 3 格必须保持清空**（蓝图与后续建造的硬约束）：
   - 平台重建会把上方 3 格（`end.height .. end.height + 2`）内的方块按**挖掘掉落**处理（容器连内容物一起掉），因此该 3×5×5 空间**不能放任何方块**，尤其不能放箱子
   - 反过来看，这个空腔正好是**刷沙机等装置的天然工作空间**，与生电需求自洽
   - 平台重建在**每次玩家/实体到达后**执行（下一 tick，聚合同一 tick 的多次到达），不再要求"附近有玩家"
4. **返回（末地 → 主世界）**：
   - **玩家**：Paper 对玩家从末地返回走 `PlayerRespawnEvent`（reason `END_PORTAL`），**不触发 `PlayerPortalEvent`**；现有 `PlayerEvents.onPlayerRespawn` 会按 `extras.respawnAtIsland` 送到岛 home
   - **非玩家实体**：`EntityPortalEvent` 改写为**本岛返回点**；返回点默认 `(islandX, 64, islandZ)`，岛主可用 `/is setreturn` 修改（聊天框显示坐标 + 二次确认）
   - 返回传送门仍由 `acidIslandEnd.schem` 建在该岛区域内，**与到达平台保持距离**；蓝图里应直接放 `end_portal` 方块（只放"框架+眼睛"粘贴不会触发激活）
   - ⚠️ **不要把返回门建在到达平台同一格**：那会让"到达"和"立刻返回"重叠，且平台重建的破坏范围会波及它
5. **装置的兼容性结论**：刷沙机等装置只要建在**本岛到达点所在的区块**即可正常工作；服务端需要保证：
   - 实体目的地点 = 本岛到达点（第 1 点）
   - 到达区块保持加载（原版 15 秒 + 玩家在场；无人值守见 D13，第一阶段不做）
   - 不因区域保护插件（WorldGuard）拦截实体落地（**必须实测**：WorldGuard 的 `BUILD DENY` 会不会影响实体传送/方块形成，以及世界全局 `BUILD DENY` 对末地世界的影响）
6. **无人值守装置（延后，D13=A）**：如果需要"人不在末地也能跑"，需要引入区块加载（插件 chunk ticket / 假人 / `ChunkRegenerator` 风格的常加载）。**第一阶段不做**，列为独立课题（涉及性能与平衡）
7. **可能的反作用**：放开所有实体意味着
   - 掉落物/箭可以跨维度 —— 到达点在本岛区域，跨岛搬运被区域隔离自然挡住（`ItemDropEvents` 还会进一步限制可见性）
   - 玩家可以把自己的物品用传送门"传送"到自己的末地区域（这属于生电玩法的一部分，建议接受）
   - 怪物/生物可以由玩家自己运到末地（生电需求，建议接受）
   - 需要防止"把别人的传送门当成自己的装置入口"：由既有 `ExploitEvents.onPortalEvent`（他人岛屿传送门限制）覆盖，但要注意它**只处理 `PlayerPortalEvent`**，实体路径需确认是否也要加同类校验

#### 4.3.3 P0 必须实测的 5 件事

> **前提**：方案默认"平台跟随 `setTo()` 落到各岛自己的坐标"。这一条是整个 D12 口径的技术基础，**必须最先验证**；若不成立，只能退化为"公共平台 + 分发"，届时需要重新讨论 D12。

| 验证项 | 为什么 | 若不成立的兜底 |
|--------|--------|----------------|
| 改写 `getTo()` 后，末地平台是否生成在**新的目标点** | **D12 的技术基础**（每岛一块独立平台） | 若不跟随，则无法做到"每岛独立"，需回头重新讨论 D12（退化为公共平台 + 分发） |
| 玩家/实体的**落点相对平台的高度**（Y+1 / Y+2？） | 决定 `setTo` 的 Y 以及玩家会不会卡进平台 | 按实测结果调整 `end.height` 与蓝图高度 |
| 平台重建时"上方 3 格破坏并掉落"是否照旧生效，且**只影响到达点附近** | 决定蓝图能否在附近放返回门、说明牌 | 若影响范围更大，需要在进入后 1 tick 内保护/恢复方块，或把返回门移得更远 |
| 实体（沙子/掉落物/箭）过门后是否落到**改写后的目的地点** | 生电装置能否建在本岛的前提 | 若实体不受 `setTo` 影响，需要"实体到达后 1 tick 内二次传送"的兜底方案 |
| WorldGuard 区域与全局 `BUILD DENY` 是否影响实体落地与装置 | 装置可能因权限被拦 | 对末地世界放宽相关 flag，或对到达点单独设 flag |

### 4.4 【补充】末地世界的生成器形态 —— **已决策：D1=A（自定义虚空世界）**

- **自定义虚空生成器**（`AcidEndChunkGenerator`，以 `AcidNetherChunkGenerator` 为模板）
  - 只提供单群系（`Biome.THE_END` 或可配置）+ 固定出生点 + 空区块体（是否加 y=0 基岩层见下）
  - 不要用原版末地生成器：中央主岛 + 环形外岛会与"每人一块区域"的网格严重冲突，而且 `generateStructures(false)` 也挡不住地形本身
  - 好处：区域隔离干净、无原版结构干扰、无折跃门、无末地城
  - **代价（已接受）**：没有末地城/末地船。相关资源已确认有其他来源：**鞘翅**由主世界幻翼膜挑战链提供、**末地门框架**由 `ClassEnd` 挑战章节提供（见 §4.2），潜影壳等如需再议
- **y=0 底部：不加（已决策 D10=A）** → **纯虚空世界**，掉下去即死亡。生成器不需要在 y=0 放基岩。由于到达平台是每岛独立的一份，玩家在末地的活动基本被限制在自己的区域内（平台之间的虚空就是天然隔离）
- 群系：末地生物群系可用 `the_end / end_highlands / end_midlands / small_end_islands / end_barrens`。建议参照下界新增 `Settings.general_defaultEndBiome` + `options.general.defaultEndBiome` 配置（下界对应 `general_defaultNetherBiome`）。注意 `biomes.yml` 里已有的 `the_end` 条目是**主世界岛屿群系覆盖**用的，与末地维度的群系是两回事，不要混用
- 生成结构：`generateStructures(false)`；`getDefaultPopulators` 返回空

### 4.5 【补充】返回路径（比进入更容易被忽略）

- 原版末地只有"击杀龙后才出现的返回传送门"，A 方案下**永远不会有** → 不处理的话玩家会被困在末地
- **已决策（D12）**：
  1. 每个岛屿的末地蓝图 `acidIslandEnd.schem` 内自带一个**返回传送门**（可用的末地传送门方块 + 框架，或用框架 + 命令触发），玩家进入 → 回到自己主世界岛屿；**不要**依赖"死亡回城"
  2. 末地世界出生点（= 兜底点 = 原点主城）里的返回传送门由主城建造方提供，作为兜底路径（目的地点改写失败、管理员传送等场景）回到主世界出生点；插件**不**在主城放置任何方块
  3. 提供 `/is end`（进末地）与 `/is end leave`（或复用 `island home`）作为传送门坏掉时的兜底命令；是否需要命令由你定（**生电装置依赖固定坐标，命令兜底不会影响装置**）
- 实现细节：末地 → 主世界的 `PlayerPortalEvent` 里 `getTo()` 也要改写（默认是主世界出生点），按末地侧区域名反查岛屿，落到该岛主世界安全点

### 4.6 【补充】无岛屿玩家、访客、兜底点 —— **已决策：D6=A（无岛屿拒绝进入）**

- **无岛屿玩家（已决策 S1）**：进不了末地。在 `PlayerPortalEvent`（`END_PORTAL`，来源为主世界且无岛屿归属）里直接取消并提示
  - 注意区分两种情况：①**主世界出生点/公共区域里的末地传送门**（如果有人搭）②**岛屿上的末地传送门**。S1 下前者也应拒绝
  - 提示文案要给出下一步指引（"你需要先创建岛屿才能进入末地"）
- **末地兜底点（fallback）**：由于每岛都有独立到达平台，**不需要**为玩家做"中枢/公共设施"；但世界出生点仍需要一个落点，现在**与末地主城合一**（原点 `(0, end.height, 0)`，config `end.fallback.x/z`）：
  1. 世界出生点/目的地点改写失败时的落点
  2. **插件不生成任何方块**（早期实现会铺 9×9 黑曜石平台并清空上方 4 格，每次启动都会覆盖主城，已移除）；主城由服主建造维护
  3. 不可破坏展示区（`__global__` 的 `BUILD: DENY` 自动保证）
  4. ⚠️ 若 P0 证实"原版平台跟随 `setTo()`"，未归属岛屿的实体落到原点时仍会**触发原版 5×5 平台重建**并破坏主城方块 —— 需要在 P0 后决定是接受、把实体兜底改到主城旁的空地，还是取消实体兜底（见 §7.2）
- **访客（在别人岛上用别人的传送门）**：现有 `ExploitEvents.onPortalEvent` 会拦截"使用他人岛屿的传送门"，末地沿用该规则即可。**但要确认**：`ExploitEvents` 只处理 `PlayerPortalEvent`，而 §4.3 放开了实体 —— 别人岛上的实体（掉落物/装置）过门后落到对方末地区域，是否需要额外校验（建议：默认放行，因为装置归属就是岛主本人；若担心"外来实体跨岛搬运"再加来源校验）

### 4.7 【补充】岛屿删除 / 重置 / 迁移

- `IslandLogic.clearIsland()` 目前串联"主世界 → 下界 → afterDeletion"，需要插入**末地区域清理**（第三段），并在清理前把末地区域内的玩家送回主世界出生点（照抄下界那段的做法，`IslandLogic.java:169`）
- `WorldGuardHandler.removeIslandRegion()` 目前只移除 `island`/`nether` 两个区域，需要加 `end`
- 岛屿迁移（`/is admin` 相关命令）后，末地坐标随岛屿坐标自动变化（因为我们用同 X/Z）→ 迁移后要**重建末地区域并重新粘贴末地蓝图**，否则区域和建筑留在原地
- 岛屿蓝图缺失时的降级路径：`IslandGenerator` 里 `netherFile == null` 会退回默认下界蓝图；末地要有同样的降级（缺 `acidIslandEnd.schem` 时跳过粘贴，但要保证入口平台仍可用）

### 4.8 【补充】生物生成与末地特色玩法

- **末影人**：末地的主要资源来源（末影珍珠）；但要限制"跨岛传送"（末影人会瞬移，可能带着方块跑到别人区域）→ 复用 `IslandBorderEvent` 的边界保护思路
- **幻翼**：`SpawnEvents` 已有 `phantomsInNether` 开关，末地应加 `phantomsInEnd`（末地不会有幻翼是原版行为，但要显式处理，否则玩家在末地过夜刷出幻翼会显得很怪）
- **其他刷怪**：潜影贝、末影螨等按现有 `SpawnEvents` 上限体系处理；末地是否启用"岛屿刷怪上限"需要确认
- **末地改造（可选，对齐下界体验）**：`NetherTerraFormEvents` 的"挖掘转化"在末地可以做成"破坏末地石概率返还末地石/紫颂/末影珍珠"之类。**建议第一阶段不做**，等基础维度跑通再加（避免一次引入太多变量）
- **末地石/紫颂可循环性**：若不启用转化，末地石不可再生（可接受，与空岛"资源有限"一致）；**需要你确认**

### 4.9 【补充】世界规则与环境细节

- `WorldManager.getDefaultWorldGenerator()` 走"名字以 `nether` 结尾"判断下界生成器，末地要加 `end` 后缀判断（`_the_end` 也要能命中）
- 末地没有天气/昼夜；`MultiverseHook` 里对 spawn 的处理、`setupWorld()` 的"安全出生点"检查要复核（末地没有地面时 `isSafeLocation` 会失败并触发 `createSpawn()` 放金块——必须避免这种脏逻辑）
- 末地世界出生点（环境属性）**显式设为原点 `(0, end.height, 0)`（config `end.fallback.x/z`）**，即全服共用的末地"主城"，同时是兜底点。原因：①原版"任何世界 → 末地"的默认目标就是世界出生点 ②目的地点改写失败时的兜底 ③主城作为公共区域固定下来。原点不会被分配岛屿（`options.general.spawnSize` 会让岛屿避开出生点区域），所以主城不会与任何岛的区域重叠
- ⚠️ 主城区域不要与岛屿区域重叠；另外若 P0 证实原版平台跟随 `setTo()`，任何未归属实体落到原点都会重建 5×5 平台并破坏主城方块（见 §4.6 第 4 点，P0 后决策）
- `isSkyAssociatedWorld()` 里"世界名以主世界名为前缀"的判定对 `_the_end` 成立（`worldName + "_the_end"` 以 `worldName` 开头）→ 命名保持这个约定即可
- 高度：末地维度是 `the_end` 类型，可建范围 **Y 0–255**（不是主世界的 -64..319）；入口 Y 单独配置 `end.height`（当前 64，默认 `island_height`，不要复用 `nether.height`）

### 4.10 【补充】经济与等级体系

- **岛屿等级**：下界方块参与 `ChunkSnapshotLevelLogic` 计分并带 `activate-at.level` 门槛。末地是否计分？
  - 计分 → 需新增 `end` 区块快照（性能开销 + 需要 `activate-at` 门槛设计）
  - 不计分 → 末地变成"纯建造空间"，玩家在末地盖的东西不影响等级，可能降低建造动力
  - **推荐第一阶段不计分**，视玩法反馈再定（改动面小、可后加）
- **方块限制（BlockLimitLogic）**：`PlayerEvents` 的方块上限按"岛屿归属"统计，末地是否纳入需要明确（下界是通过 `LimitLogic` 把主世界+下界生物数合并统计的，末地要照做还是排除）
- **挑战**：需要新增末地相关挑战（对齐 `enter_nether`），例如 `enter_end`、`end_stone`、`chorus` 等；`challenges.yml` 现有 `netherportal` 前置链可参照。**这一块改动量小、收益直接，建议纳入第一阶段**
- **占位符 / API**：`PluginInfo`、`PlayerInfo` 的 `getIslandNetherLocation()` 需要对应的 `getIslandEndLocation()`；若第三方 API 需要暴露末地坐标，`uSkyBlock-API(v2)` 也要评估

### 4.11 【补充】危险与漏洞面

| 风险 | 说明 | 对策 |
|------|------|------|
| 掉虚空 | 末地无地面（D10=A 纯虚空），Y 最低处即死亡 | 到达平台即落脚点；`onSpawnDamage` 式的虚空保护要确认末地是否纳入（建议**不**纳入末地，掉下去就该死） |
| **末地平台重建破坏建筑** | 每岛到达平台每次有实体进入都会重建，并**破坏平台正上方 3 格方块（掉落成物品）** → 玩家若在到达平台上方/附近建东西或放箱子，每次进末地都会拆一次 | **每岛划定"平台保护空腔"**：平台正上方 3×5×5 不放任何持久方块/容器（蓝图与玩家说明都要写清）；P0 实测破坏范围 |
| **装置/农场副作用** | 放开全部实体后，掉落物、箭、生物都能跨维度 → 可能被用于"把自己的东西送进末地"（生电玩法，建议接受）或"送到兜底点"（掉落物堆积） | 到达点强制本岛；兜底点掉落物定期清理；必要时加"进入提示" |
| 复用下界传送门 | 主世界现有点火式下界传送门不能通末地 | 末地传送门必须是 `END_PORTAL` 方块（框架 + 末影之眼激活）。**需要决定框架由谁提供**：蓝图里预置"已激活的传送门方块"（玩家拆不掉/或用 WorldGuard 保护），或给玩家 12 个框架方块自行激活（原版激活需要 12 眼，成本高） |
| 末地传送门框架被拆 | `END_PORTAL_FRAME` 是方块，玩家可破坏自己的（正常）也可能破坏别人的 | 依赖 WorldGuard 区域保护即可 |
| 末地水晶爆炸 | 可破坏地形 | 拦 `EntityExplodeEvent`（参照 `NetherTerraFormEvents.onGhastExplode`、`GriefEvents`） |
| 末影人搬方块 | 会把别人区域的方块搬走/搬入 | 复用 `IslandBorderEvent` / `EntityChangeBlockEvent` 的现有保护 |
| 潜影贝/末影珍珠骑乘逃脱 | 用珍珠/紫颂果跨区域传送 | 珍珠落点在他人区域时取消或落回原岛（需新增判定） |
| 跨维度背包 | 主世界/下界已用 Multiverse-Inventories 的 `skyblock` 组共享 | 末地必须加入同一组，否则玩家进末地"丢装备"（严重事故） |

### 4.12 【补充】`isSkyAssociatedWorld()` 的纳入范围（隐性大坑）

`isSkyAssociatedWorld()` 有 **50+ 处调用**，一旦把末地纳入，下面这些系统会**自动**开始在末地生效，需要逐一确认是否符合预期：

| 调用方 | 在末地是否想要 | 备注 |
|--------|----------------|------|
| `GriefEvents`（防破坏） | ✅ 要 | 末地也要防苦力怕/凋灵 |
| `IslandBorderEvent`（跨边界保护） | ✅ 要 | 这是"每人一块区域"的核心保护 |
| `ItemDropEvents`（掉落物仅成员可见） | ✅ 要 | |
| `PlayerEvents` 访客保护/PvP/重生/传送校验 | ✅ 要 | 需确认重生逻辑在末地的兜底 |
| `WorldGuardEvents`（封禁拦截） | ✅ 要 | |
| `SpawnEvents`（刷怪上限/试炼笼） | ⚠️ 复核 | 末地的刷怪上限策略要单独定 |
| `ToxicEvents`（毒水） | ⚠️ 复核 | 末地本无水；纳入后酸水能否在末地生效需明确（可能产生意外玩法/漏洞） |
| `SulfurEvents`（硫磺） | ⚠️ 复核 | 与末地主题无关，建议**排除** |
| `SignEvents` / `SignLogic`（木牌商店） | ⚠️ 复核 | 末地是否允许木牌商店 |
| `PlayerEvents` 方块上限 | ⚠️ 复核 | §4.10 |
| `ToolMenuEvents`（树苗工具菜单） | ❌ 排除 | 末地不需要 |
| `biome` 切换（`BiomeCommand`） | ❌ 排除 | 末地群系固定 |
| `AltarEvents` | ❌ 已排除（用 `isSkyWorld()`），**故意保持** | 祭坛只在主世界 |
| `ChallengeCommand` / 进度统计 | ⚠️ 复核 | 末地是否算"在岛屿上" |

**推荐做法**：把 `isSkyAssociatedWorld()` 的语义明确为"插件管辖的所有世界（含末地，当末地启用时）"，然后在**明确不该生效**的系统里改用更窄的判定（新增 `WorldManager.isSkyEnd()` / `isSkyNether()`）。**不要**为了省事让末地复用 `isSkyWorld()`。

### 4.13 【补充】Multiverse 与外部插件

- `MultiverseHook` 需要新增 `registerEndWorld()`：`importWorld(ImportWorldOptions.worldName(...).environment(THE_END).generator("uSkyBlock").useSpawnAdjust(false))`，`setScale(1.0)`、`setRespawnWorld(...)` 与下界一致
- **Multiverse-Inventories**：末地必须加入 `skyblock` 组（`linkNetherInventory()` 现有实现是硬编码两参，建议重构为通用的"把若干世界加入 skyblock 组"）
- **Multiverse 自带的传送门模块**：Multiverse-Core 的 portal 功能会接管原版传送门链接。需要确认它对 `END_PORTAL` 是否插手，以及是否需要为末地世界关闭其传送门处理（否则可能出现"我们 `setTo` 完了，Multiverse 又改回去"的竞争）
- 其他须复核的插件行为：WorldGuard 全局区域（`setupGlobal()` 需要为末地世界调用）、WorldEdit 的 `isOuterPossible()` 前置检查（下界启用前会检查，末地是否也要）

### 4.14 【补充】运维与可观测性

- 新配置节 `end:`（方法与 `nether:` 平级，风格照抄）：
  ```yaml
  end:
    enabled: true
    height: <island_height>                # D9：单独配置，不复用下界的 y/2
    chunk-generator: us.talabrek.ultimateskyblock.world.EndChunkGenerator
    schematicName: acidIslandEnd
    activate-at:
      level: 100                           # D7=B：末地计分的门槛
    # D8=A 时不配置 terraform 相关内容
  ```
- **同步更新 `uSkyBlock/config.yml`**（服务器实际读取的配置，**不改这里等于没生效**）；`version` 递增（当前 110）
- 群系配置：`options.general.defaultEndBiome`（参照 `defaultNetherBiome`）
- `plugin.yml` 需要新增权限（如 `usb.end.use`；`usb.biome.the_end` 已存在）
- 调试命令：建议加 `/usb admin end` 类的诊断（区域是否存在、到达点是否安全、末地世界是否加载、`enter_end` 挑战状态），便于线上排查
- 旧数据兼容：服务器如果**曾经**存在一个叫 `world_the_end` 或类似名字的世界（`isSkyAssociatedWorld` 目前排除 `THE_END`，说明历史上可能有别的末地世界），上线前要确认不会冲突/串数据
- **配置真相源**：本插件读取 `plugins/uSkyBlock/` 下的运行时配置；仓库内 `uSkyBlock-Core/src/main/resources/` 的默认值**已过时**（缺 `ClassEnd`/`enter_end`/`the_end` 等）

---

## 5. 决策点：已全部确认

### 5.1 已确认决策表

| ID | 问题 | 决策 |
|----|------|------|
| **D1** | 末地地形形态 | **A. 自定义虚空世界**，每人一块区域（放弃原版地形/末地城/折跃门） |
| **D2** | 末影龙 | **A. 不生成龙**；末影龙战斗与龙蛋计划用 **MythicDungeon 做独立副本**，不在本次范围 |
| **D2b** | 鞘翅/龙蛋等来源 | **已在主世界实现**：`uSkyBlock/challenges.yml` 幻翼膜挑战链给鞘翅（2% / 稳定）；无需在末地侧再做 |
| **D3** | 末地城/折跃门 | 虚空世界不生成；外岛内容统一走未来副本，不做 |
| **D4** | 非玩家实体过门 | **放开所有实体通过**（生电需求）。因此必须正面设计到达点（§4.3），不能靠"禁止实体过门"回避 |
| **D5** | 末地传送门框架 | **已通过 challenge 实现**：运行时 `uSkyBlock/challenges.yml` 的 `ClassEnd` 章节奖励 `end_portal_frame:1`，玩家自备 12 颗末影之眼激活 |
| **D6** | 无岛屿玩家 | **A. 直接拒绝**进入末地 |
| **D7** | 末地是否算岛屿等级 | **B. 算**，参考下界做法（需 `end` 区块快照 + `end.activate-at.level` 门槛） |
| **D8** | 末地石/紫颂可再生 | **A. 第一阶段不做转化**（不可再生） |
| **D9** | 末地到达 Y 高度 | **A. 单独配置 `end.height`，默认取 `island_height`** |
| **D10** | y=0 底部 | **A. 纯虚空**，不加基岩底 |
| **D11** | `isSkyAssociatedWorld` 纳入 | **A. 全局纳入 + 逐系统排除**（排除清单见 §4.12 / D11b） |
| **D11b** | 末地生效的子系统 | 纳入：`GriefEvents`、`IslandBorderEvent`、访客/封禁保护、`ItemDropEvents`、`SpawnEvents`、`SignEvents`；排除（不选）：`ToxicEvents`（毒水）、`ToolMenuEvents`（树苗菜单）、biome 切换、`AltarEvents`（本来就限主世界） |
| **D12** | 到达点形态 | **每个岛屿自己一份独立的 5×5 黑曜石平台**（原版机制，但每岛一份，坐标 = 该岛 X/Z + `end.height`）；**返回传送门由蓝图建在该岛区域内，与到达平台保持距离**。平台"每实体进入即重建、破坏上方 3 格"的特性必须按每岛平台来考虑（§4.3） |
| **D13** | 无人值守装置 | **Q1-A. 第一阶段只保证"人在场可用"**；无人值守（区块常加载）单列课题 |
| **D14** | 生电支撑项 | **Q2-A. 轻量保护**：兜底点掉落物自动清理 + 异常堆积告警；**不加**实体/掉落物上限（避免误伤正常装置） |

### 5.2 仍待你补充（非阻塞）

| ID | 问题 | 说明 |
|----|------|------|
| **D15** | 返回传送门相对到达平台的确切位置 | 蓝图设计参数。约束：①不要与到达平台同格（会"进门即返回"）②不要落在"平台正上方 3 格"的破坏范围内 ③最好在平台视线/步行可达范围内。需要你给一个方位偏好（例如 +8 格）或直接由建造者定 |
| **D16** | 末地群系取值 | 建议新增 `options.general.defaultEndBiome`，默认 `the_end`；是否允许玩家像主世界那样切换末地群系？（`biome` 切换已决定排除在末地之外） |
| **D17** | 末地生效的子系统复核 | 你选了 `SpawnEvents`（刷怪上限）与 `SignEvents`（木牌商店）在末地生效。**若后续在末地做刷怪塔类生电，刷怪上限可能成为阻碍**，届时需要放宽；见 §4.8/§4.12 |
| **D18** | 兜底点 | **已更新：兜底点 = 世界出生点 = 原点 `(0, end.height, 0)` 的末地"主城"**（config `end.fallback.x/z`）。插件**不**在主城放置任何方块（旧实现的 9×9 平台已移除，避免重启覆盖主城）；未归属岛屿的实体落到主城时是否触发原版平台重建，待 P0 结论 |

---

## 6. 实施路线（分阶段，每阶段可独立验证）

> 原则：先把"世界 + 区域 + 传送 + **每岛独立到达平台** + 生电"闭环跑通，再叠玩法；每阶段结束都要能在服务器上实测通过。

**P0 — 前置实测（不写业务代码，但必须做完）**

1. **平台是否跟随目标点**（最关键）：临时监听器改写 `PlayerPortalEvent.getTo()` 到某个非出生点坐标，验证 5×5 平台生成在**该坐标**。这是"每岛独立平台"的技术基础
2. **落点高度**：玩家/实体的落点相对平台的 Y 偏移（决定 `setTo` 的 Y 与蓝图高度）
3. **破坏范围实测**：在到达点上方放测试方块 + 箱子，验证"重建时破坏上方 3 格并掉落"的范围与行为（容器是否连内容物掉落）
4. **实体是否跟随**：`EntityPortalEvent` 改写后，沙子/掉落物/箭落点是否也跟随到该坐标 → 决定刷沙机装置建在哪里
5. **刷沙机链路验证**：搭最小刷沙机（沙子 → 末地传送门 → 末地到达平台），确认复制链路 + 需要服务端保证什么
6. **WorldGuard 影响**：区域与全局 `BUILD DENY` 是否影响实体落地、方块形成、装置运行
7. **Multiverse**：portal 模块是否与我们的 `setTo()` 抢控制权；Multiverse-Inventories 三世界共享背包是否生效

**P1 — 世界与区域骨架**

1. `Settings`：新增 `end_enabled / end_height / end_schematicName / end_chunkGenerator / end_fallbackPoint` + `general_defaultEndBiome`；`config.yml` 新增 `end:` 节；**同步 `uSkyBlock/config.yml`**；`version` 递增
2. `world/EndChunkGenerator.java`（纯虚空 + 单群系 + 固定世界出生点；**不加 y=0 基岩**）
3. `WorldManager`：`getEndWorld()`、`isSkyEnd()`、`getDefaultWorldGenerator()` 支持 end、`isSkyAssociatedWorld()` 纳入末地（含 D11b 排除清单）
4. `WorldGuardHandler`：区域 id `<name>end`、`getIslandNameAt/getIslandRegionAt` 支持 `end` 后缀、`updateRegion()` 同步创建、`removeIslandRegion()` 一并删除、`setupGlobal()` 对末地调用
5. `MultiverseHook.registerEndWorld()` + 重构 `linkNetherInventory()` 为通用分组（**末地必须加入 `skyblock` 组**，否则丢装备）
6. `IslandGenerator`：建岛时粘贴 `acidIslandEnd.schem`（蓝图 = **返回传送门**，位置按 D15）
7. `IslandLogic.clearIsland()` 串联末地清理；`PlayerInfo.getIslandEndLocation()`；`LimitLogic` 生物统计纳入末地

**P2 — 传送与生电闭环（核心）**

1. 新建末地监听器（建议 `event/EndPortalEvents.java`）：
   - `PlayerPortalEvent`（`END_PORTAL`）双向改写目的地 + 岛屿归属校验 + 无岛屿拒绝（D6）
   - `EntityPortalEvent`（`ENDER`）**不取消**，改写目的地到**本岛到达平台**（§4.3）
2. 到达平台落地：确认每岛平台的坐标约定（X/Z = 岛屿坐标，Y = `end.height`）+ 落点高度；按 P0 结论决定是否需要"进入后 1 tick 修复"兜底
3. 与既有 `PlayerEvents.onPlayerPortal`（任务门槛/挑战）、`ExploitEvents.onPortalEvent`（他人岛屿限制）打通优先级
4. **`enter_end` 的触发点（暂缓）**：挑战已在 `uSkyBlock/challenges.yml` 里定义，但代码里的触发**已临时移除**（`PlayerEvents.onPlayerPortal` 的 `END_PORTAL` 分支、`EndPortalEvents.onPlayerPortal` 各留 TODO）；旧实现会在大厅末地/被取消的传送门/无岛玩家上误完成。后续改成“传送真正被接受后再发”，见 §7.2
5. 蓝图返回传送门（D15 位置）+ 末地兜底点（D18）+ `/is end` 类命令兜底（可选）

**P3 — 防破坏与生态**

1. 末影龙不生成（D2=A）+ 末地水晶放置/引爆拦截 + 爆炸保护
2. `SpawnEvents`：`phantomsInEnd` 开关、刷怪上限在末地的策略、`isSkyEnd` 判定
3. 末影人跨岛传送、珍珠/紫颂果跨区域传送的边界处理
4. 虚空死亡/重生策略复核（`PlayerEvents.onPlayerRespawn`、`onSpawnDamage`）
5. 兜底点掉落物清理与异常堆积告警（D14）

**P4 — 等级与玩法（可延后）**

1. 末地等级计分（D7=B）：`ChunkSnapshotLevelLogic` / `AweLevelLogic` 接入 `end` 区域 + `end.activate-at.level` 门槛
2. 末地相关挑战链（除 `enter_end` 外，如末地石/紫颂主题）
3. 末地改造（挖掘转化，D8 若将来要做）
4. placeholder/API 暴露（`uSkyBlock-API` v1/v2 评估）

**P5 — 生电专项（按需）**

1. 无人值守装置的区块加载方案（D13）
2. 装置性能观测：TPS 观测、异常装置识别（**不加**实体/掉落物硬上限，避免误伤）

**每阶段验收清单（建议写成测试）**
- 建岛 → 末地区域与蓝图（返回门）是否生成、坐标是否正确
- 岛主进末地 → **落到自己岛上那块独立的到达平台**；访客 → 被拦；无岛屿 → 被拒绝（D6）
- **实体（沙子/掉落物/箭/船）过门 → 落到本岛到达平台；刷沙机可复现复制；到达平台上方永远保持 3 格空气**
- 返回门 → 回到自己岛（不是世界出生点）；`/island home` 正常
- 进入末地 → **（暂缓，触发点已移除）** `enter_end` 挑战被标记完成
- 删除岛屿 → 主世界/下界/末地三处区域与方块全部清空，区域玩家被送回
- 服务器重启后 → 末地世界、区域、Multiverse 注册、背包共享均恢复

---

## 7. 实施状态

### 7.1 已实现（待服务器实测）

**P1 世界与区域骨架**

| 位置 | 内容 |
|------|------|
| `Settings.java` | 新增 `end_enabled / end_height / end_activateAtLevel / end_schematicName / end_chunkGenerator / end_fallbackX / end_fallbackZ` + `general_defaultEndBiome`；读 `end.*` 配置；末地启用前同样检查 `WorldEditHandler.isOuterPossible()` |
| `world/AcidEndChunkGenerator.java`（新增） | 纯虚空生成器 + 单群系（`general_defaultEndBiome`）+ 兜底出生点 |
| `world/WorldManager.java` | `getEndWorld()`（世界名 `<worldName>_the_end`，`Environment.THE_END`，`generateStructures(false)`）、`getEndWorldName()`、`isSkyEnd()`、`getEndGenerator()`、`getDefaultWorldGenerator()` 识别 end、`isSkyAssociatedWorld()` 纳入末地、末地出生点=原点主城（**不建方块**）+ 末影龙定时清除 + **末影龙 bossbar 清理**（`DragonBattle#getBossBar()` 的 `setVisible(false)`/`removeAll()`，每 20 tick 兜底；进入末地时立即清） |
| `handler/WorldGuardHandler.java` | 区域 id `<name>end`（Y 0..255）、`updateRegion()` 同步创建、`removeIslandRegion()` 一并删除、`getEndRegionAt()`、`getIslandNameAt/getIslandRegionAt` 抽出 `getIslandNameFromRegion()` 统一后缀处理（**顺手修掉了原来的 `substring(length-6)` 硬编码**）、岛屿上锁同时锁下界与末地、**区域版本 `VERSION` 14 → 15**（`general.regionVersion` 不匹配时 `protectIsland()` 会重建 island/nether/end 三套区域） |
| `hook/world/MultiverseHook.java` | `registerEndWorld()`；`linkNetherInventory()` 重构为通用 `linkInventories()`（**末地加入 `skyblock` 组，避免丢装备**） |
| `island/IslandGenerator.java` | 建岛时读 `end.schematicName` + `<scheme>End` 兜底并粘贴末地蓝图；额外世界蓝图不再出现在可选岛屿蓝图列表 |
| `island/IslandLogic.java` | `clearIsland()` 串联为 主世界 → 下界 → 末地 → afterDeletion；新增 `getEndLocation()` |
| `player/PlayerInfo.java` + `uSkyBlock-API/.../api/PlayerInfo.java` | 新增 `getIslandEndLocation()` |
| `uSkyBlock.java` | `locationIsOnEndIsland()`、`playerIsOnOwnIsland()` 纳入末地、启动时重置 `skyBlockEndWorld` |
| `island/LimitLogic.java`、`event/IslandBorderEvent.java` | 生物数量统计 / 边界保护纳入末地（顺带修掉下界禁用时空指针） |
| `bootstrap/Services.java` | 末地世界 `WorldGuardHandler.setupGlobal()` |
| `config.yml`（默认 + `uSkyBlock/` 运行时） | 新增 `end:` 节（含 `activate-at.level`、`fallback.x/z`，`fallback` 即原点主城/世界出生点）、`options.general.defaultEndBiome`、`options.spawning.phantoms.end`；`version` 110 → 111 |

**P2 传送与生电闭环**

| 位置 | 内容 |
|------|------|
| `event/EndPortalEvents.java`（新增） | ①`PlayerPortalEvent`（`END_PORTAL`，主世界岛屿 → 末地）：改写为本岛玩家到达点 `(X+0.5, end.height, Z+0.5)`，无岛/未链接直接拒绝；玩家**离开**末地不在这里处理（Paper 触发的是 `PlayerRespawnEvent(END_PORTAL)`，由 `PlayerEvents.onPlayerRespawn` 送岛 home） ②`EntityPortalEvent`（`ENDER`）：主世界 → 末地改写到本岛实体到达点 `(X+0.5, end.height+1, Z+0.5)`；末地 → 主世界改写到**本岛返回点**（默认 `(X,64,Z)`，`/is setreturn` 可改）；无法解析时送兜底点 ③`buildArrivalPlatform()`：在到达点重建 5×5 黑曜石平台，清空上方 3 格并**按挖掘掉落**（含容器内容物），每次到达后下一 tick 重建（聚合同 tick 多次到达） ④末地水晶放置拦截 + 末地水晶/末影龙爆炸取消 + **取消末影龙生成**（`EntitySpawnEvent`） ⑤**末影珍珠跨区域拦截**：珍珠落点不在任何岛屿区域内时取消（防止掉虚空，也防止用珍珠跳过区域隔离） ⑥玩家进入末地时立即清 bossbar |
| `island/IslandInfo.java` | 新增 `getReturnPoint()`/`setReturnPoint()`（`general.returnPointX/Y/Z`，默认 `(X,64,Z)`；`resetIslandConfig()` 一并清除） |
| `command/island/SetReturnCommand.java`（新增） | `/is setreturn`：岛主在自己主世界岛上使用，聊天框显示"所在方块中心"坐标，点击 `[确认]`（或 `/is setreturn confirm`）二次确认后写入岛返回点；60 秒超时，另支持 `cancel` |
| `bootstrap/Listeners.java` | 按 `Settings.end_enabled` 条件注册 `EndPortalEvents` |
| `event/PlayerEvents.java` | `onPlayerPortal` 的 beginner 门槛只对下界门生效（`END_PORTAL` 直接归 `EndPortalEvents`）；`onEntityPortal` 的 beginner 门槛也只对 `NETHER` 生效，避免拦掉末地实体；`enter_end` 触发点仍留 TODO |
| `event/SpawnEvents.java` | 新增末地幻翼开关 `phantomsInEnd` + `setPhantomsInEnd()` |

**状态**：`./gradlew :uSkyBlock-Plugin:buildPlugin` 通过，产物 `uSkyBlock-Plugin/build/libs/uSkyBlock-Plugin.jar`。

**P4 岛屿等级（D7=B）**

| 位置 | 内容 |
|------|------|
| `island/level/CommonLevelLogic.java` | 新增 `getEndLocation()`（世界不可用时返回 null，避免 NPE） |
| `island/level/ChunkSnapshotLevelLogic.java` | 快照链扩展为 主世界 → 下界 → 末地；抽出 `addRegionBlocks()`；末地方块在总分达到 `end.activate-at.level` 后计入等级 |

### 7.2 尚未完成 / 阻塞项

| 项 | 说明 |
|----|------|
| **`acidIslandEnd.schem` 蓝图** | **仓库与服务器 `uSkyBlock/schematics/` 里都没有**。缺它时插件不会报错（会跳过粘贴），但**返回传送门不存在**，玩家进末地后有去无回 —— 需要建造并放入蓝图。蓝图里要**直接放 `end_portal` 方块**（只放"框架+眼睛"粘贴不会触发激活），并避开到达平台正上方 5×5×4 空腔 |
| **P0 实测未做** | §4.3.3 的 5 项（平台位置、落点高度、破坏范围、实体落点、刷沙机链路）必须在测试服验证；**逐项步骤见 [`END-P0-CHECKLIST.md`](END-P0-CHECKLIST.md)**。**平台是否跟随 `setTo()` 已有源码结论：不跟随**（Paper 在事件之前按原版 end spawn 建平台），所以现有实现改为插件自己铺平台；P0 第 1 项要**先临时关掉插件自己的平台重建**再测，否则测到的是我们铺的平台 |
| **末地世界首次生成** | 需要重启服务器让新世界创建、Multiverse 导入、区域建立；上线前建议先确认旧世界命名不冲突 |
| **现有岛屿补末地区域** | **已用「区域版本 bump」方案解决**：`WorldGuardHandler.VERSION` 14 → 15，老岛的 `general.regionVersion`（`14 zh_CN`）因此失配，`protectIsland()` 会重建 **island + nether + end 三套区域**。部署后执行一次 `/usb protectall` 即可覆盖全部老岛（数量 = `plugins/uSkyBlock/islands/` 下的岛屿文件数），输出 `N new regions were created` 应等于该数量；没跑之前老岛玩家在末地会被 `__global__` 的 BUILD DENY 挡住、且不算"在自己的岛上" |
| 岛屿迁移后的末地重建 | **不需要**：仓库里没有岛屿迁移/重定位功能（`IslandLocatorLogic` 只负责分配新位置），岛屿位置不会变，因此末地区域随坐标天然稳定 |
| 珍珠/紫颂果跨区域传送 | **已处理**：末影珍珠落点不在任何岛屿区域内时取消传送（`EndPortalEvents.onPearlTeleport`）；紫颂果在末地无紫颂植株自然生长的前提下影响很小 |
| **原点主城 / 兜底点** | 已改为：世界出生点 = 兜底点 = 原点 `(0, end.height, 0)`（config `end.fallback.x/z`），插件**不建任何方块**（原 9×9 黑曜石平台 + 清空上方 4 格已移除）。主城由服主建造维护。**注意**：实体兜底（`getEndEntityFallbackLocation`）现在只改落点、不再铺平台；原版平台建在 `Level.END` 的 `(100,48,0)`，与主城/本岛无关，但需确认 `Level.END` 指向哪个世界（大厅的 `world_the_end` 还是我们的 `_the_end`） |
| **`enter_end` 触发点（TODO）** | 挑战已定义，但**代码里暂时不发**：`EndPortalEvents.onPlayerPortal` 留了 TODO。旧实现会在传送门被校验/取消之前完成挑战（无岛、未链接、他人岛屿、大厅末地都会误完成）。后续应改成"传送真正被接受后（例如到达后 / `PlayerChangedWorldEvent`）再发" |
| `WorldEditHandler.isOuterPossible()` | 末地启用沿用了下界的前置检查，需确认它在当前服务器配置下返回 true（否则末地会被自动禁用并打警告） |

---

## 8. 参考

- [PlayerPortalEvent (Paper 26.x Javadoc)](https://jd.papermc.io/paper/26.1.2/org/bukkit/event/player/PlayerPortalEvent.html) — `END_PORTAL` 的 `getTo()` 语义；注意 `setCreationRadius` 的 "end portal target platforms always appear at the target location" 容易被误读，实际平台在事件之前按原版 end spawn 创建（见下条）
- [Paper `EndPortalBlock` 补丁](https://github.com/PaperMC/Paper/blob/main/paper-server/patches/sources/net/minecraft/world/level/block/EndPortalBlock.java.patch) — 平台创建先于 `handlePortalEvents`；玩家从末地返回走 `findRespawnPositionAndUseSpawnBlock(..., RespawnReason.END_PORTAL)`
- [Paper `EndPlatformFeature` 补丁](https://github.com/PaperMC/Paper/blob/main/paper-server/patches/sources/net/minecraft/world/level/levelgen/feature/EndPlatformFeature.java.patch) — 5×5 平台 + 清空上方 3 格 + `PortalCreateEvent(END_PLATFORM)`
- [PlayerRespawnEvent.RespawnReason](https://jd.papermc.io/paper/1.21.9/org/bukkit/event/player/PlayerRespawnEvent.RespawnReason.html) — `END_PORTAL`：玩家通过末地门离开末地
- [SPIGOT-4770](https://hub.spigotmc.org/jira/browse/SPIGOT-4770) — "PlayerPortalEvent doesn't fire from End"
- [EntityPortalEvent (Paper 26.x Javadoc)](https://jd.papermc.io/paper/26.1.2/org/bukkit/event/entity/EntityPortalEvent.html) — 非玩家实体过门、`PortalType.ENDER`
- [End Platform (Minecraft Wiki)](https://minecraft.wiki/w/End_Platform) — 5×5 黑曜石平台、每次实体进入都重建、破坏上方 3 格并掉落、Java 版非玩家实体同样触发、1.20.5 起到达后区块保持加载 15 秒
- [EnderDragonChangePhaseEvent (Paper Javadoc)](https://jd.papermc.io/paper/1.21.9/org/bukkit/event/entity/EnderDragonChangePhaseEvent.html) — 若将来改选 B/C 方案会用到
- [Multiverse-Core config.yml (Portals)](https://github.com/Multiverse/Multiverse-Core/wiki/config.yml-(Portals)) — 传送门模块行为
- [End gateway (Minecraft Wiki)](https://breezewiki.com/minecraft/wiki/End_gateway) — 折跃门生成条件（龙死后、主岛 96 格外）；虚空方案下不涉及
- 代码基线：`world/WorldManager.java`、`world/AcidNetherChunkGenerator.java`、`handler/WorldGuardHandler.java`、`island/IslandGenerator.java`、`island/IslandLogic.java`、`hook/world/MultiverseHook.java`、`event/PlayerEvents.java`、`event/ExploitEvents.java`
