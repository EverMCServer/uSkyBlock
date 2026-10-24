# 末地 P0 实机验证清单

> 目的：验证 `END-DESIGN.md` §4.3.3 的 5 项假设。**第 1 项是整个"每岛独立到达平台"设计的技术前提**，结论决定是否需要回头改设计（D12）。
> 需要一台可重启的测试服 + 一把末影之眼/可放置的末地传送门（或用 `/setblock` 造一个门）。

---

## 0. 准备

1. 部署本次构建的 `uSkyBlock-Plugin.jar`（已通过 `buildPlugin`）。
2. `plugins/uSkyBlock/config.yml` 确认已有 `end:` 节（本次已加入）：
   ```yaml
   end:
     enabled: true
     height: 49
     activate-at:
       level: 100
     schematicName: acidIslandEnd
     chunk-generator: us.talabrek.ultimateskyblock.world.AcidEndChunkGenerator
     fallback:
       x: 0
       z: 0
   ```
   `end.fallback` 同时是**世界出生点 / 兜底点**，也就是原点的主城；**插件不在这里生成任何方块**（主城由服主建造维护）。
3. 重启服务器，确认日志里 **没有** `The End DISABLED, since islands cannot be chunk-aligned!`。
   - 若出现该警告 → `WorldEditHandler.isOuterPossible()` 返回 false，末地会被自动关闭，后续全部无法验证（需要先查这条前置检查）。
4. 确认世界已创建：`<worldName>_the_end`（默认 `world_acidisland_the_end` 之类，取决于 `options.general.worldName`）。
   - 用 `/mv list` 或看服务器根目录是否出现该世界文件夹。
   - **注意**：这是新世界，建议先备份或确认与旧世界不冲突。
5. **给现有岛屿补末地区域**：`WorldGuardHandler.VERSION` 已从 `14` 提到 `15`，老岛的 `general.regionVersion`（`14 zh_CN`）失配，所以执行一次
   ```
   /usb protectall
   ```
   预期：进度跑完显示 `N new regions were created`，N = `plugins/uSkyBlock/islands/` 下的岛屿文件数（部署时用 `ls islands | wc -l` 核对）；`plugins/WorldGuard/worlds/world_acidisland_the_end/regions.yml` 里出现全部 `<name>end` 区域。
   - 不跑这一步的话：老岛玩家进末地后会被末地世界的 `__global__`（BUILD DENY）挡住，不能建造，且 `playerIsOnOwnIsland()` 在末地返回 false。
   - 只让岛主登录也会触发单岛重建（`protectIsland()`），但 `/usb protectall` 一次覆盖所有岛。
6. 找一块**没有岛屿**的坐标（例如岛屿网格的间隙），记下它的 4 个角 —— 后面测"平台是否跟随"要用它。

---

## 1. 【最关键】平台是否跟随 `setTo()` 的落点

**为什么**：整个"每岛一块独立到达平台"的设计依赖这一点。源码结论（Paper `EndPortalBlock#getPortalDestination`）是：**平台在触发传送门事件之前、按原版 `ServerLevel.END_SPAWN_POINT` 创建，`setTo()` 不会移动平台**；所以我们改成由插件自己铺平台。要验证原版行为，必须先**临时注释掉** `EndPortalEvents.buildArrivalPlatform` / `ensureArrivalPlatformLater`（或把调用去掉），否则测到的是插件铺的平台。

**做法**
1. 玩家 A 拥有岛屿，岛坐标记为 `(X, Z)`。
2. 在 A 的岛上建一个可用的末地传送门（12 个框架 + 12 眼；或直接用本次挑战奖励的框架）。
3. 记录进末地**之前**：
   - `/execute in <end_world> run setblock 100 48 0 minecraft:gold_block`（把**平台那一层**的方块替换成标记；不要放 47，47 在平台下方，测不到）
   - 记录 A 岛 X/Z（`/island info` 或看 `PlayerInfo`），并 `setblock <X> 63 <Z> minecraft:gold_block`（本岛预期位置放个标记）
4. 玩家 A 走进末地传送门。

**判定**
- ✅ **通过（原版平台跟随）**：玩家落在 `(X, ~49, Z)` 附近，本岛位置出现 5×5 黑曜石平台 → 现有实现仍可用（但我们仍自己铺平台）。
- ❌ **不通过（原版平台固定）**：玩家被送到 `(100, ~49, 0)` 附近、或 `(X,49,Z)` 没出现平台 → 证明平台不跟随；这是**预期结论**，每岛平台必须由插件 `buildArrivalPlatform` 铺（已实现）。

**记录**：玩家实际落点坐标 = `______`；插件平台出现位置 = `______`；原版平台是否出现在 `(100,48,0)` = `______`

**前置确认**：先确认服务器上 `Level.END`（主 End 维度，通常是 `world_the_end`）存在且已加载；如果它不存在，`getPortalDestination` 会直接返回 null，**传送门事件根本不会触发**。另外 P0 第 1 项如果看到"平台不跟随"，不要据此改回公共平台方案 —— 我们已经用"插件自己铺"解决。

---

## 2. 落点相对平台的高度

**做法**：在 §1 通过的平台上，观察玩家脚下方块与自己所处的 Y。

**判定**
- 玩家脚下那一层应是黑曜石（`end.height - 1`），玩家站在 `end.height`，X/Z 居中（`+0.5`）。
- 若玩家被卡在方块里、或落在平台上方悬空 1 格，需要调整 `end.height` 与落点 Y。

**记录**：玩家 Y = `______`；黑曜石 Y = `______`

---

## 3. 平台重建的破坏范围

**为什么**：决定蓝图里返回门能不能放在附近，以及玩家在附近放箱子会不会每次进末地都被炸开。

**做法**
1. 在到达平台正上方 3 格内放：一个箱子（里面塞几个物品）、一层石头。
2. 让**另一个**实体（丢一个物品/扔一颗末影珍珠，或第二个人）从传送门进末地。
3. 观察箱子和石头的下场。

**判定**
- 预期（wiki 口径）：平台重建 → 上方 3 格方块**按被玩家挖掘掉落**，箱子连内容物一起掉落。
- 若破坏范围远大于"平台正上方 3×5×5"，需要在进入后 1 tick 内做方块保护/恢复。

**记录**：箱子是否掉落 = `______`；内容物是否掉落 = `______`；被破坏的横向范围 = `______`

---

## 4. 实体过门是否跟随改写后的落点（生电前提）

**为什么**：刷沙机等装置要求实体落在**本岛**的固定到达点。若 `EntityPortalEvent` 的改写不生效，装置会跑到别处。

**做法**
1. 在玩家 A 的岛上做一个最小装置：把沙子/沙砾推进末地传送门（或直接往门里丢物品、射一箭）。
2. 观察这些实体在末地的落点。

**判定**
- ✅ 实体落在 A 岛到达平台上方 1 格，即 `(X+0.5, end.height+1, Z+0.5)`；玩家在 `(X+0.5, end.height, Z+0.5)`。
- ❌ 实体落在 (100,48,0)/兜底点 → 需要"实体到达后 1 tick 内二次传送"的兜底方案。

**记录**：实体落点 = `______`

---

## 5. 刷沙机链路是否成立

**做法**：在 A 岛搭一个标准刷沙机（沙子 → 末地传送门 → 末地到达点），跑几轮。

**判定**
- 沙子是否在末地稳定堆积、原世界那侧是否也保留。
- 到达平台上方 3 格始终为空（装置自身不要占用这 3 格）。

**记录**：是否复制成功 = `______`；是否需要玩家在末地附近 = `______`

> ⚠️ **2026-10-18 已验证的结论**：**自定义末地维度下原版刷沙永远不会复制**，与 `allow-unsafe-end-portal-teleportation` 无关 —— Paper 的 `FallingBlockEntity#teleport()` 只在跨**原版** `minecraft:the_end` 维度时才置 `forceTickAfterTeleportToDuplicate`，我们的 `world_acidisland_the_end` 是自定义维度，判定必为 false。测试服实测：临时把实体目的地改到 `world_the_end`（原版 the_end）后复制立刻正常。详见 [`END-DESIGN.md`](END-DESIGN.md) §7.2。**本项不再是"能不能复制"的验收，而是"末地要不要迁到原版 the_end 世界"的后续决策。**

---

## 6. WorldGuard / 权限是否影响实体落地与装置

**做法**
1. 在 A 岛末地区域内让实体落地、形成方块（如沙子落地成块）。
2. 尝试在区域外（虚空中）放置方块。

**判定**
- 区域内：岛屿成员应能正常建造/实体落地。
- 区域外：应被 `__global__` 的 `BUILD: DENY` 拦住（这是预期保护，不是 bug）。

**记录**：区域内是否正常 = `______`；区域外是否被拦 = `______`

---

## 7. Multiverse / 背包共享

**做法**
1. `/mv list` 确认末地已注册，环境为 THE_END。
2. 玩家带着明显物品（例如一组钻石）主世界 → 末地 → 下界 → 主世界，来回走。

**判定**
- 三个世界背包一致（**不一致就是严重事故**：玩家会"丢装备"）。
- 若不一致 → 检查 `Multiverse-Inventories` 的 `skyblock` 组是否包含末地（`linkInventories()` 已加入，需确认 MV-Inventories 版本行为）。

**记录**：背包是否一致 = `______`

---

## 8. 基础功能回归（顺带确认）

| 检查项 | 期望 |
|--------|------|
| 无岛屿玩家进末地传送门 | 被拒绝 + 提示"需要先创建岛屿" |
| 岛屿上的传送门进末地 | 落到本岛到达平台 |
| 末地返回门 | 玩家回到本岛（`PlayerRespawnEvent(END_PORTAL)` → 岛 home）；实体回到**本岛返回点**（默认 `(X,64,Z)`） |
| `/is setreturn` | 岛主在自己主世界岛上执行 → 聊天框显示坐标 + `[确认]`；点击/输入 `/is setreturn confirm` 后生效；非岛主被拒绝；实体从末地返回落在该点 |
| 实体从末地返回 | 扔一个物品/沙子进返回门 → 落在 `/is setreturn` 设定的点，而不是世界出生点 |
| `enter_end` 进度 | 本次不验证：触发点已实装（`EndPortalEvents.onPlayerPortalMonitor` 记录 `enter_end` 进度，玩家在 `/c` 里点击领取），但不是 P0 阻塞项 |
| 建岛 | 末地出现对应区域与蓝图（当前**没有蓝图文件**，所以只有平台，没有返回门） |
| 删岛 | 末地区域与方块一并清空；区域内的玩家被送回 |
| 珍珠在末地往区域外扔 | 被取消 + 提示（不让你掉虚空） |
| 末地放置末地水晶 | **不处理**：允许放置/引爆（原版复活仪式），管理员按需处理 |
| 末地 bossbar | **不处理**：龙战期间会有末影龙 bossbar；管理员击杀龙后消失 |
| 末影龙生成 | **不处理**：vanilla 龙战会生成龙；上线后管理员手动击杀原点附近的龙。⚠️ 击杀会生成原版 Exit Portal/龙蛋，可能覆盖主城 |
| 岛屿上锁 | 末地区域同步上锁（访客进不来） |

---

## 9. 已知的必然现象（不是 bug）

1. **没有返回传送门**：`acidIslandEnd.schem` 蓝图尚不存在，插件会静默跳过粘贴。玩家进末地后只能靠 `/island home` 或死亡回来 —— **上线前必须补这个蓝图**（蓝图里直接放 `end_portal` 方块，框架+眼睛粘贴不会激活）。
2. **到达平台上方 3 格会被破坏并按挖掘掉落**：这是原版末地平台机制（含容器内容物）；装置的"工作空间"正好利用它，但**不要**把箱子等容器放在这 3 格里。
3. **末影龙/龙战不做任何处理**：插件不删龙、不拦水晶/爆炸、不清 bossbar；上线后管理员手动击杀原点的龙。**击杀龙会在原点附近生成原版 Exit Portal（基岩/末地门方块）和龙蛋**，可能覆盖主城 —— 建议先杀龙再建主城，或杀完清理。
4. **末地是共享世界**：所有岛屿在同一个 `_the_end` 世界里，靠 WorldGuard 区域隔离 + 虚空隔断。
5. **`Multiverse-NetherPortals` 会在末地 `(100,50,0)` 铺一块 5×5 黑曜石平台**（`end-platform-drop-blocks: true`，被替换的方块按物品掉出）。这是它把 `world_acidisland` ↔ `world_acidisland_the_end` 自动链接（`portal-auto-link-when.end.suffix: _the_end`）后的副作用；实体/玩家的目的地已被本插件在 HIGHEST 覆盖成"本岛到达点"，不会去那里，**功能无影响**。只是别在末地主城 `(100,50,0)` 附近规划建筑。同理不要为了清掉它把 `end.suffix` 改成不命中的值——那会让 MV 直接取消过门事件（详见 `END-DESIGN.md` §4.13）。
6. **末地门的"所有权"归本插件**：`EntityPortalEvent` / `PlayerPortalEvent` 都按 `ignoreCancelled = false` 处理，并在解析出本岛目的地后 `setCancelled(false)`。所以换过门事件被别的插件取消（MV 无链接世界时的行为）时，岛屿末地门仍然可用。
