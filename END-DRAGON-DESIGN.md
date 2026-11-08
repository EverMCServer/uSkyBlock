# 复活末影龙系统（`/is enddragon`）设计方案

> 状态：**已实现（2026-11-08），待服务器实测**。本文保留为设计记录。
> 已按最新口径修正：**8 级 = 8×64 = 512 个末影水晶**；黑曜石柱按**原版 10 根**处理。
> 实现代码在 `uSkyBlock-Core/src/main/java/us/talabrek/ultimateskyblock/enddragon/`；上线前仍需完成 §14.2 的 P0 实测。

**已确认的决策：**

| 项 | 结论 |
|----|------|
| 祭坛位置 | 自定义末地主城 `world_acidisland_the_end` 的 `dragon_altar` region |
| 首龙 | 已经正常击杀过；系统只负责之后的重复挑战 |
| 奖励表 | 由本方案先给一套可配置的默认表，后续再调数值 |
| 领取地点 | 任何地方都可以领取；开始挑战仍要求祭坛区域 |
| 消耗 | 1 级 4 个，每级翻倍，8 级 512 个（8 组） |
| 柱 | 原版 10 根，不自己造柱/补水晶 |

---

## 1. 结论速览

| 项目 | 方案 |
|------|------|
| 入口 | `/is enddragon`（可选别名 `/is ed`） |
| 区域 | 自定义末地主城 `world_acidisland_the_end` 的 WorldGuard region `dragon_altar`（可配置）；世界环境必须是 `THE_END` |
| 菜单 | 27 格：1 个说明 + 8 个难度 + 状态/领取/关闭 |
| 消耗 | 1 级 4 个末影水晶，每级翻倍；8 级 512 个（8 组） |
| 复活 | 调用 `World#getEnderDragonBattle()` 的 `initiateRespawn(...)`，走原版 4 水晶 → 10 根柱水晶 → 龙 |
| 伤害限制 | 仅发起者 + 其岛屿成员；龙部件/弹射物按来源解析；其他伤害取消 |
| 失败 | 3600s 未受到有效伤害 → 强制结束、清理、重置战斗状态；不退还消耗 |
| 成功 | 龙死亡 → 岛屿记录 `_reward_dragon_level` 和 `_max_dragon_level`；`/is enddragon` 领取（任何地方），背包满则保留 |
| 原版掉落 | 系统复活的击杀默认抑制原版 XP/物品掉落，改用配置奖励（避免 4 水晶刷 12000 XP） |
| 持久化 | 末地世界的 **World PDC**（存 `level.dat`）：进行中状态 + 待领取奖励；龙实体 PDC 辅助标记；重启恢复 |
| 并发 | 一个世界只有一条龙 → 全局同时只允许 1 场挑战 |
| 首杀 | 已正常击杀过；系统只做重复挑战，首杀的龙蛋/返回门保持不变 |

**推荐落地顺序**：先做 P0 技术验证（见 §14.2），再写业务代码。P0 不通过就不进入实现。

---

## 2. 现状与硬约束

### 2.1 Paper 26.2 的龙战 API

`World#getEnderDragonBattle()` 的 Javadoc 明确：

- 只要世界环境是 `Environment.THE_END` 就返回 `DragonBattle`；
- 即使当前没有龙/没有开战，也返回一个“状态持有者”；
- 因此**自定义末地世界**（`world_acidisland_the_end`，`Environment.THE_END`）也可以拿到 `DragonBattle`。

`DragonBattle` 可用方法（Paper 26.2）：

- `getEnderDragon()`：当前龙，龙死后为 null；
- `getBossBar()`：原版 Boss 血条；
- `getEndPortalLocation()`：返回门位置；
- `hasBeenPreviouslyKilled()` / `setPreviouslyKilled(boolean)`；
- `initiateRespawn()`：等价于“玩家在返回门放了 4 个水晶”；
- `initiateRespawn(Collection<EnderCrystal>)`（Paper）：指定仪式水晶；传空集合表示不可取消的复活；
- `getRespawnPhase()`：`START` / `PREPARING_TO_SUMMON_PILLARS` / `SUMMONING_PILLARS` / `SUMMONING_DRAGON` / `END` / `NONE`；
- `getHealingCrystals()`：10 根黑曜石柱上的治疗水晶；
- `getRespawnCrystals()`：本次复活使用的仪式水晶；
- `resetCrystals()`：重置柱水晶的光束/无敌状态。

原版复活流程就是：

```
放 4 个水晶在返回门
  → START
  → PREPARING_TO_SUMMON_PILLARS
  → SUMMONING_PILLARS（依次重生 10 根黑曜石柱上的水晶）
  → SUMMONING_DRAGON（所有水晶光束指天）
  → END（龙出现）
```

这正好对应需求里的“依次重生 10 个黑曜石柱上的水晶，然后开打”，**不需要插件自己造柱或自己补水晶**。

### 2.2 关键约束

1. **一个世界只有一条龙**：`DragonBattle` 是世界级状态，不能按岛实例化。因此系统天然是“全局单场挑战”，不能两个岛同时开。
2. **首龙必须正常击杀**：`initiateRespawn` 要求 `hasBeenPreviouslyKilled() == true`。首杀才会生成返回门和龙蛋；之后 `previouslyKilled` 永久为 true，不再生成龙蛋。所以本系统是“重复挑战”，首杀仍走原版。
3. **10 根柱是原版的**：召唤完成后校验 `getHealingCrystals().size() == 10`，不是 10 就说明竞技场/世界配置有问题。
4. **龙战在世界原点附近**：原版龙战锚定在世界的龙战 origin（通常是原点）；`dragon_altar` 区域应该覆盖这个原点/返回门/10 根柱。不要把祭坛建在离原点很远的地方。
5. **刷怪上限已放行**：`SpawnEvents` 已把 `ENDER_DRAGON` 排除在岛屿刷怪上限外（注释说明取消会留下 ghost dragon UUID），复活出来的龙不会被刷怪限制拦截。
6. **现有菜单基建**：`gui/InventoryGui` + `InventoryButton` + `GuiManager` 是新的 GUI 体系；本菜单应使用它，不要继续膨胀 `SkyBlockMenu`。
7. **持久化方式**：使用末地世界的 `PersistentDataContainer`（`level.dat`），不用独立 YAML 文件；龙实体 PDC 只做辅助标记，权威状态在 World PDC。

### 2.3 与现有末地设计的关系

[`END-DESIGN.md`](END-DESIGN.md) 当前写的是“插件不处理末影龙，vanilla 龙战照常运行，管理员手动击杀原点的龙”。本方案上线后：

- 原点首龙仍由管理员/玩家正常击杀一次；
- 之后通过本系统重复复活、挑战、领奖；
- `END-DESIGN.md` §4.2、§7.2 的“不处理末影龙”需要改成“首杀 vanilla，之后由 `/is enddragon` 管理”；
- `uSkyBlock/handbook.yml` 里“原版末影龙还在 / 原点那条归管理员打 / 龙蛋 → 打原点末影龙”的说明也要同步。

---

## 3. 组件与职责

| 组件 | 职责 |
|------|------|
| `EndDragonCommand` | `/is enddragon` / `/is ed`；权限；打开菜单；可选 CLI 子命令 |
| `EndDragonMenu` | 27 格主菜单：说明、8 难度、状态、领取、关闭 |
| `EndDragonConfirmMenu` | 高成本操作的二次确认 |
| `DragonFightManager` | 单例核心：状态机、开始/失败/成功/领取、计时、伤害门禁、重启恢复 |
| `DragonFightPersistence` | 末地 World PDC 的四个 active 字段 |
| `DragonIslandData` | 主世界岛屿中心区块 PDC 的 `_max_dragon_level` / `_reward_dragon_level` |
| `DragonDifficulty` | 每级配置（cost / health / damage / reward / timeout） |
| `DragonModifier`（后续） | 难度特殊机制的扩展点，第一阶段不实现具体机制 |
| `EndDragonEvents` | `EntityDamageByEntityEvent`、`EntityDeathEvent`、可选的 gateway/quit 监听 |
| `WorldGuardHandler` 扩展 | `getRegion(World, id)` / `isInRegion(Location, id)` |
| `AdminCommand` 扩展 | `/usb enddragon status|reset|reload|check` |
| `EndDragonConfig` | 读 `end.dragon.*`；难度表；默认值/校验 |

---

## 4. 区域与入口

### 4.1 判定方案

玩家必须同时满足：

1. 当前世界 `world.getEnvironment() == World.Environment.THE_END`；
2. 该世界存在 region id = `dragon_altar`（配置 `end.dragon.altar-region`，默认 `dragon_altar`）；
3. 玩家坐标 `region.contains(blockX, blockY, blockZ)`。

实现细节：

- 在 `WorldGuardHandler` 新增 `getRegion(World, String)` 与 `isInRegion(Location, String)`；
- 不要用 `getIslandLookupY()`，因为祭坛不是岛屿区域，且可能只覆盖特定 Y；
- region id 大小写不敏感；WorldGuard 内部一般是小写；
- 已确认祭坛在**自定义末地主城** `world_acidisland_the_end`，因此使用 `WorldManager.getEndWorld()` 的 `DragonBattle`；实现时仍建议按“region 所在世界”解析，不要把世界名写死，方便以后把祭坛挪到大厅原版末地；
- 如果配置了 `end.dragon.world`，玩家必须在该世界，且 region 也必须在该世界；默认留空，自动取玩家所在世界；
- 区域不存在 / WorldGuard 未加载：提示“祭坛区域未配置”，并打一次 WARN；管理员用 `/usb enddragon check` 排查。

### 4.2 命令行为

- `/is enddragon` 任何时候都可以打开菜单；
- **开始挑战**：只在区域内可用；点击时再次校验（防止打开菜单后走出去）；
- **领取奖励**：任何地方可用（奖励记在岛屿 PDC，不要求回祭坛）；
- 玩家没有岛屿：可以打开菜单领取已有奖励，但不能开始；提示先创建岛屿；
- 权限：`usb.island.enddragon`（默认 true，挂到 `usb.use`）；
- 如果将来祭坛挪到非插件管理的世界（大厅末地），本插件只负责龙战逻辑，不负责那个世界的传送门/区域管理。

### 4.3 祭坛区域建议

- region 覆盖：返回门 + 10 根黑曜石柱 + 龙的活动范围；
- 区域中心尽量贴近世界原点/龙战 origin；
- 区域 flag 建议允许 PVP、允许使用，但 BUILD 仍由服主控制；
- 祭坛区域内不要有会被龙破坏的重要建筑；龙破坏方块的保护见 §8.5。

---

## 5. 菜单

### 5.1 主菜单（27 格）

| 槽位 | 内容 | 说明 |
|------|------|------|
| 4 | 说明（`DRAGON_HEAD` 或 `KNOWLEDGE_BOOK`） | 规则、消耗表、3600s、伤害限制、领取说明 |
| 10–17 | 8 个难度（`END_CRYSTAL`） | 等级、消耗、当前持有、状态、奖励预览 |
| 18 | 当前挑战状态 | 进行中：难度/龙血量/剩余时间/发起者；空闲：无 |
| 22 | 领取奖励 | 有待领取时高亮；否则灰色 |
| 26 | 关闭 | `BARRIER` |

- 物品 `amount` 最大 64，所以 512 的消耗用 lore 显示“8 组（512 个）”，不要试图 setAmount(512)。
- 状态着色：
  - 绿色：可挑战；
  - 红色：水晶不足；
  - 灰色：已有挑战/未开放；
  - 屏障：等级未配置。
- 说明物品的 lore 要写清楚：
  - 1 级 4 个，每级翻倍，8 级 512 个；
  - 只有发起者和岛屿成员能伤害龙；
  - 3600s 没有有效伤害就失败，消耗不退；
  - 成功后在 `/is enddragon` 领取，背包满会保留。

### 5.2 二次确认

点击难度后不直接扣费，先打开确认菜单：

- 显示：难度、消耗数量、当前持有、奖励预览、特殊说明（待定）；
- `[确认挑战]` → `DragonFightManager.start(player, difficulty)`；
- `[取消]` → 返回主菜单；
- 所有校验（区域、权限、岛屿、active、水晶数量、battle 状态）在 `start` 里重新做一遍，菜单只负责展示。

### 5.3 进行中 / 待领取

- 进行中：显示当前龙血量、剩余时间（`_dragon_last_damage_tick + timeout - world.getGameTime()`）、发起者、当前难度；
- 非发起岛成员：看到“其他岛正在挑战：岛名 / 难度”，难度按钮全部禁用；
- 待领取：显示奖励预览；点击领取调用 `claim(player)`；
- 可选“放弃挑战”按钮：仅发起者/岛主可见，二次确认后调用 `DragonFightManager.abandon()`，避免卡住时只能等 3600s。

---

## 6. 消耗

### 6.1 消耗表（已修正）

| 等级 | 水晶数 | 等价组数 |
|------|--------|----------|
| 1（原版） | 4 | 4 个 |
| 2 | 8 | 8 个 |
| 3 | 16 | 16 个 |
| 4 | 32 | 32 个 |
| 5 | 64 | 1 组 |
| 6 | 128 | 2 组 |
| 7 | 256 | 4 组 |
| 8 | 512 | 8 组 |

- 公式：`cost = 4 * 2^(level - 1)`；
- 配置仍写成显式 `cost`，方便以后微调；代码不要写死公式；
- 1 级“与原版相同”：消耗 4 个物品，同时生成 4 个临时仪式水晶；高等级仍然只用 4 个仪式水晶，多出来的是入场费。

### 6.2 计数与扣除

- 只统计玩家背包主空间（0–35）；默认不含副手、不含末影箱；在说明 lore 里写清楚；
- 开始前重新计数，不足则提示“还差 N 个”；
- 扣除时逐槽位精确扣减，不整组误删；
- 扣完 `player.updateInventory()`；
- 事务顺序：
  1. 重新校验所有条件；
  2. 计数；
  3. 扣除；
  4. 调用复活；
  5. 如果 `initiateRespawn` 返回 false（技术失败），把水晶退回、清状态；
  6. 如果龙已经出现，不退。

### 6.3 退款边界

- 召唤阶段技术失败（`initiateRespawn` false / 看门狗超时且龙从未出现）：退款；
- 战斗阶段失败（3600s 超时 / 主动放弃 / 管理员 reset）：不退款；
- 服务器崩溃导致状态丢失：按恢复逻辑判断；如果无法确认龙是否出现过，默认不退，管理员可手动补。

---

## 7. 复活流程

### 7.1 直接调用原版 API（推荐）

**不需要自己写阶段机，也不需要 `setRespawnPhase` 来启动复活。**

- `setRespawnPhase` **不能启动复活**：它只在复活已经进行时有效（`getRespawnPhase() != NONE`）；没有复活时调用会直接返回 `false`。
- 启动复活用 `initiateRespawn(...)`：
  - `initiateRespawn()`：等价于“玩家在返回门放了 4 个水晶”，但要求 4 个水晶实体已经存在；
  - `initiateRespawn(Collection<EnderCrystal>)`（Paper API）：直接指定 4 个水晶，最接近“玩家放置水晶复活”；传空集合表示不可取消的复活；
- 调用后原版自己跑 `START → PREPARING_TO_SUMMON_PILLARS → SUMMONING_PILLARS → SUMMONING_DRAGON → END`，10 根柱和龙都由原版处理；插件只观察 `getRespawnPhase()` 和 `getEnderDragon()`。

调用顺序：

```
World world = 祭坛所在世界
DragonBattle battle = world.getEnderDragonBattle()
if (battle == null) -> 失败：世界环境不是 THE_END / 服务端不支持

校验：
  battle.hasBeenPreviouslyKilled() == true     // 首龙必须先正常击杀
  battle.getEnderDragon() == null              // 当前没有龙
  battle.getRespawnPhase() == NONE             // 没有正在进行的复活

清理祭坛区域内的残留 EnderCrystal

生成 4 个临时仪式水晶（返回门四周，位置可配置）
battle.initiateRespawn(ritualCrystals)
  -> false: 清理临时水晶、退款、清状态
  -> true : 进入 SUMMONING
```

> 如果不想生成 4 个临时水晶，也可以直接 `battle.initiateRespawn(Collections.emptyList())`；区别只是没有“4 个水晶射向柱”的视觉，原版流程和 10 根柱仍然照常。`setRespawnPhase` 只建议在调试/强制阶段时使用，不要用于生产启动。

然后观察 `getRespawnPhase()`：

| 阶段 | 含义 | 插件动作 |
|------|------|----------|
| `START` | 仪式开始 | 状态保持 SUMMONING |
| `PREPARING_TO_SUMMON_PILLARS` | 准备重生柱水晶 | 状态保持 |
| `SUMMONING_PILLARS` | 依次重生 10 根柱水晶 | 状态保持；可显示进度 |
| `SUMMONING_DRAGON` | 水晶光束指天 | 状态保持 |
| `END` / `getEnderDragon() != null` | 龙出现 | 转 FIGHTING，应用难度，记录 UUID |
| `NONE` 且无龙 | 序列中断 | 看门狗判定失败 |

### 7.2 原版 10 根柱

- 祭坛按原版 10 根柱设计，**没有任何自定义柱逻辑**；
- 依赖原版 `DragonBattle` 的 `SUMMONING_PILLARS` 阶段，插件不补水晶；
- 如果祭坛在原生 `world_the_end`，`initiateRespawn` 就是原版行为，不需要额外验证；
- 如果祭坛在 `world_acidisland_the_end`（插件创建的末地世界），只要那 10 根柱是原版结构/位置，`initiateRespawn` 也会按原版处理；P0 只需确认 `getHealingCrystals().size() == 10`；
- 如果 P0 发现这个世界完全不认柱（例如世界类型/生成器导致原版 End Spike 不存在），优先把祭坛/竞技场放到原生 `world_the_end`，**不要写自定义补水晶逻辑**；`getHealingCrystals()` 不是 10 时记录 WARN，由管理员用 `/usb enddragon check` 排查。

### 7.3 召唤看门狗

- `SUMMONING` 超过 `summoning-timeout-seconds`（默认 60s）仍未出现龙 → 技术失败：
  - 移除临时水晶/残留龙；
  - 退款（如果龙从未出现）；
  - 清状态；
  - 记录日志，提示发起者。
- 原版序列通常 10–20s，60s 足够。

### 7.4 首龙、返回门、折跃门

- 首龙必须正常击杀：`hasBeenPreviouslyKilled == false` 时拒绝开始，提示“请先击杀原版首龙”；
- 首杀会生成返回门和龙蛋；系统复活**不会重复给龙蛋**；
- 每次击杀原版会生成折跃门（gateway），可能落在主城附近：
  - 折跃门是方块而不是实体，当前实现**不做额外拦截**；
  - 如需清理，后续另做方块层方案（例如龙死后扫描/移除主城附近的 `END_GATEWAY` 方块）；
- 复活时如果返回门不存在，Paper 的 `initiateRespawn` 可能自动生成一个；P0 要确认它是否会覆盖主城方块。祭坛区域应包含返回门，避免自动生成到别处。

---

## 8. 战斗规则

### 8.1 伤害门禁

监听 `EntityDamageByEntityEvent`（建议 `HIGHEST`，`ignoreCancelled = false`，自己判断）：

- 目标是 `EnderDragon` 或 `EnderDragonPart`（`getParent()` 取龙）；
- 合法来源：
  - `Player` 且是发起者，或 `challengeIsland.getMemberUUIDs().contains(uuid)`；
  - `Projectile` 且 `getShooter()` 是上述玩家（箭、三叉戟、雪球等）；
  - 默认不允许宠物/铁傀儡/其他实体，避免归属歧义；
- 其他一律 `setCancelled(true)`：
  - 其他玩家；
  - 怪物、铁傀儡、宠物；
  - TNT、水晶爆炸；
  - 火焰/岩浆/虚空/摔落/药水等环境伤害（另监听 `EntityDamageEvent`）。
- 龙处于 `DYING` 阶段时不再拦截，让原版死亡流程走完。
- 记录 `_dragon_last_damage_tick`：
  - 仅当事件未被取消、`event.getFinalDamage() > 0`、来源合法时更新；
  - 内存更新，每 5s 最多写一次 World PDC；关键节点立即写 PDC。

### 8.2 水晶保护

- 挑战期间，祭坛区域内的 `EnderCrystal` 只允许发起者/岛成员破坏；
- 其他人破坏 → 取消；
- 防止旁观者把 10 根柱水晶打掉、恶意拖到超时；
- 可选：挑战期间禁止在祭坛区域放置/引爆 TNT、重生锚等。

### 8.3 超时

- `timeout-seconds` 默认 3600；用 `world.getGameTime()` 计算；
- 每秒检查：`world.getGameTime() - _dragon_last_damage_tick >= timeout * 20` → 失败；
- 没受伤时 `_dragon_last_damage_tick` 就是召唤时间，所以 3600s 从召唤开始算；
- 服务器停机期间游戏时间不前进，相当于暂停计时。

### 8.4 失败清理（“重置 dragonfight 状态”）

目标：清掉插件状态 + 让 `DragonBattle` 回到可再次 `initiateRespawn` 的状态。

1. 内存标记 `resetting`（不持久化），停止计时，伤害门禁继续拦截；
2. `battle.setPreviouslyKilled(true)`，避免强制击杀时生成龙蛋/返回门；
3. 强制结束龙：
   - **首选** `dragon.setHealth(0)`，让原版死亡流程走完，`DragonFight` 自行把状态清回可复活；
   - 在 `EntityDeathEvent` 里识别 `resetting`：清掉落、`setDroppedExp(0)`、不发奖励；
   - 等死亡动画（约 200 ticks）后清 active；
   - 如果 `setHealth(0)` 不生效/龙不死：退路 `dragon.remove()` + `battle.resetCrystals()` + `setPreviouslyKilled(true)`，并打 WARN；
4. 移除祭坛区域内所有 `EnderCrystal`（柱水晶/仪式水晶）；
5. 清 active、释放区块 ticket、广播失败、写日志；
6. 可选 `fail-cooldown-seconds`（默认 30s），避免实体还没清干净就重开。

> “重置 dragonfight 状态”既包括插件自己的 active 状态，也包括原版 `DragonBattle` 的可复活状态；两者必须一起清。

### 8.5 龙破坏方块

- 龙在祭坛区域破坏方块可能拆掉主城/返回门；
- 建议在祭坛区域监听 `EntityChangeBlockEvent` / 爆炸事件，取消龙的破坏；
- 配置 `protect-arena: true`（默认 true）；
- 如果某个难度想做“可破坏竞技场”，后续用 `DragonModifier` 单独开。

### 8.6 成功

- 监听 `EntityDeathEvent`（`MONITOR`），目标是当前 `dragonUuid` 的龙；
- 成功后只写岛屿上的两个值：
  - `_max_dragon_level = max(当前, difficulty + 1)`，封顶 8；
  - `_reward_dragon_level = difficulty`（表示该难度奖励待领取）；
- 清空末地世界的四个 active 字段；
- 不取消死亡事件，让原版生成返回门/折跃门；
- **默认抑制原版 XP 和 item drops**（`keep-vanilla-xp: false` / `keep-vanilla-drops: false`），改用配置奖励。原因：原版龙给 12000 XP，4 个水晶就能重复刷，经济上不可接受；首杀的原版奖励不受影响；
- 龙蛋由首杀逻辑决定，系统复活的击杀默认不给龙蛋，除非奖励表里显式配置；
- 广播成功；奖励不落世界账本，只记录岛屿的 `_reward_dragon_level`。

---

## 9. 奖励

### 9.1 奖励配置

- 每级一份 `Reward`，复用挑战系统的 `Reward` / `ItemStackUtil` 解析（items/currency/xp/commands/text）；
- 不再做奖励快照/账本：岛屿只记 `_reward_dragon_level`，领取时按当前配置生成该难度奖励；
- 默认表（**占位，数值按经济再调**）：

| 等级 | 物品奖励 | 货币 | 经验 |
|------|----------|------|------|
| 1 | `dragon_breath` ×8 | 2,000 | 1,000 |
| 2 | `dragon_breath` ×16 | 3,000 | 1,500 |
| 3 | `dragon_head` ×1 | 4,500 | 2,000 |
| 4 | `dragon_head` ×2 | 6,500 | 3,000 |
| 5 | `dragon_egg` ×1 | 9,000 | 4,000 |
| 6 | `dragon_egg` ×1 + `dragon_head` ×1 | 12,000 | 5,500 |
| 7 | `elytra` ×1 + `dragon_egg` ×1 | 16,000 | 7,000 |
| 8 | `elytra` ×1 + `dragon_egg` ×2 + `dragon_head` ×1 | 24,000 | 10,000 |

- 如果不想让重复挑战产龙蛋：把 5–8 级的 `dragon_egg` 换成 `dragon_head` / `elytra` / `dragon_breath`，或直接删掉；配置改即可，不需要改代码。
- 首杀才给龙蛋；系统复活的龙蛋只来自上表。上表会给 `dragon_trial` 挑战提供重复蛋来源，需要确认是否接受；如果不接受，把 5–8 级的蛋去掉。
- 原版 12000 XP 默认抑制，上表的经验是“替代值”，不是额外叠加。

### 9.2 岛屿上的奖励记录

奖励不再按玩家做账本，只记在岛屿中心区块的 PDC：

| key | 含义 |
|-----|------|
| `_max_dragon_level` | 已解锁最高难度，初始 1，胜利后解锁下一级 |
| `_reward_dragon_level` | 待领取奖励的难度；0 = 没有待领奖励 |

规则：

- 胜利：`_reward_dragon_level = difficulty`，`_max_dragon_level = max(当前, difficulty + 1)`；
- 领取成功：`_reward_dragon_level = 0`；
- 开启新挑战前必须 `_reward_dragon_level == 0`；
- 岛屿重置/删除时由 `ChunkRegenerator` 清空这两个值：它会删除被重建区块里所有 `uskyblock` namespace 的 PDC key，两个 island key 也在其中；不再额外写显式 clear。

### 9.3 领取流程

`/is enddragon` → 领取按钮：

1. 读岛屿 `_reward_dragon_level`；为 0 → 没有奖励；
2. 按该难度当前配置生成 items/currency/xp/commands；
3. **模拟背包空间**：复制 36 格 storage，`addItem` 奖励物品；
4. 有 leftover → 提示“背包空间不足，奖励保留”，**不发奖、不丢物品**，`_reward_dragon_level` 保持；
5. 空间足够 → `addItem`、给经验/货币、执行 commands，然后把 `_reward_dragon_level` 设为 0；
6. 任何情况下都不把奖励丢到地上（避免区域禁止捡取/丢弃）。

---

## 10. 状态机与持久化

### 10.1 状态

没有持久化的状态枚举，只有四字段：

- `_dragon_initiator` 不存在 → 没有进行中挑战；
- `_dragon_initiator` 存在、`_dragon_uuid` 不存在 → 召唤中（`SUMMONING`）；
- `_dragon_uuid` 存在 → 战斗中（`FIGHTING`）；
- 失败后的 `RESETTING` 只是内存里的临时清理标志，不持久化。

### 10.2 存储位置

**末地世界 PDC（World PDC）**，只存四个字段：

| key | 类型 | 内容 |
|-----|------|------|
| `uskyblock:_dragon_uuid` | `STRING` | 当前 active 的末影龙 UUID；召唤中时不存在 |
| `uskyblock:_dragon_initiator` | `STRING` | 开启挑战的玩家 UUID |
| `uskyblock:_dragon_difficulty` | `INTEGER` | 难度 |
| `uskyblock:_dragon_last_damage_tick` | `LONG` | 最后受伤的 `world.getGameTime()`；没受伤就是召唤时间 |

**主世界岛屿中心所在区块的 Chunk PDC**，只存两个字段：

| key | 类型 | 默认 | 内容 |
|-----|------|------|------|
| `uskyblock:_max_dragon_level` | `INTEGER` | 1 | 已解锁最高难度 |
| `uskyblock:_reward_dragon_level` | `INTEGER` | 0 | 待领取奖励的难度；0 = 无 |

- 区块 PDC 随区块保存；岛屿重置/删除时 `ChunkRegenerator` 会删除被重建区块里所有 `uskyblock` namespace 的 key，所以这两个 key 一起归零；
- 没有 `dragonfight.yml`，也没有奖励账本/快照；
- 世界 PDC 和区块 PDC 都是地图数据的一部分。

### 10.3 写入策略

- 启动时从末地世界 PDC 读取 active；岛屿数据在菜单/战斗需要时从区块 PDC 读取；
- 写入都是 `STRING`/`INTEGER`/`LONG` 原始类型，没有 YAML、没有文件 I/O；
- `_dragon_last_damage_tick` 每次有效伤害更新；PDC 是内存操作，随世界/区块 autosave、正常关服保存；
- **代价**：服务器崩溃时可能回滚到上一次自动保存；正常关服不受影响。

### 10.4 重启恢复

1. 读末地世界 PDC；没有 `_dragon_initiator` → 没有 active；
2. 有 initiator、没有 `_dragon_uuid`：
   - 龙已存在 → 补 `_dragon_uuid`，应用难度，进入战斗；
   - `getRespawnPhase() != NONE` → 继续等；
   - 既无龙也无序列 / 超过召唤超时 → 技术失败/退款、清 active。
3. 有 `_dragon_uuid`：
   - 龙存在且 UUID 匹配 → 用 `world.getGameTime() - _dragon_last_damage_tick` 判断 3600s 超时；
   - 龙不存在/UUID 不匹配 → 失败清理，不发奖励。
4. 岛屿 PDC 不受 active 影响；胜利后 `_reward_dragon_level` 保留，重启后仍可领取；
5. 超时按游戏 tick 计算，服务器停机期间游戏时间不前进，相当于暂停计时。

### 10.5 岛屿删除 / 成员变动

- 挑战中岛屿被删除/重置：`clearIsland` 触发的 `ChunkRegenerator` 会清掉区块里的 `uskyblock` PDC；active 在下一次 tick 发现发起者没有可用岛屿后失败清理；
- 发起者退岛/被踢：发起者仍可继续（他是发起人）；岛屿成员按发起者**当前**岛屿实时解析；
- 岛屿改名：当前没有改名功能；active 不存岛名，不受影响；
- 发起者被封禁：不影响挑战；管理员可 reset；
- 岛屿奖励：`_reward_dragon_level` 记在岛上，任何岛屿成员都可以领取；岛屿重置/删除时一起清空。

---

## 11. 并发与边界

| 场景 | 行为 |
|------|------|
| 另一个岛/另一个玩家点开始 | 拒绝：“当前已有末影龙挑战（岛 X，难度 N）” |
| 同一岛多个成员同时点 | 主线程串行，第一个成功，第二个看到 active |
| 发起者离线 | 挑战继续，计时继续；成员可打；奖励记在岛上，任何岛屿成员可领 |
| 所有参与者离线 | 计时继续，3600s 后失败 |
| 龙被非参与者击杀（理论上被门禁拦住） | 不结算奖励；管理员 reset |
| 龙被服务器/其他插件清除 | 恢复时判定失败；管理员 reset |
| 世界卸载/末地关闭 | active 保留，等世界恢复；超过宽限期失败 |
| 服务器重启 | 见 §10.4 |
| 管理员 reset | 强制清理，不退款（除非召唤阶段技术失败）；记录操作人 |

---

## 12. 配置与权限

### 12.1 `config.yml`（`end.dragon`）

```yaml
end:
  dragon:
    enabled: false                # 默认关闭，竞技场/首龙确认后再在运行时打开
    altar-region: dragon_altar
    world: "world_acidisland_the_end"   # 已确认自定义末地主城；留空则自动取玩家所在世界
    timeout-seconds: 3600
    summoning-timeout-seconds: 120
    fail-cooldown-seconds: 30
    keep-arena-loaded: true
    protect-arena: true
    keep-vanilla-xp: false        # 系统复活的击杀不保留原版 12000 XP
    keep-vanilla-drops: false
    difficulties:
      1:
        cost: 4
        display-name: "原版"
        max-health: 200.0
        damage-multiplier: 1.0
        reward:
          items:
            - dragon_breath:8
          currency: 2000
          xp: 1000
          commands: []
      2:
        cost: 8
        display-name: "略强"
        max-health: 300.0
        damage-multiplier: 1.1
        reward:
          items:
            - dragon_breath:16
          currency: 3000
          xp: 1500
      8:
        cost: 512
        display-name: "终局"
        max-health: 1000.0
        damage-multiplier: 2.0
        reward:
          items:
            - elytra:1
            - dragon_egg:2
            - dragon_head:1
          currency: 24000
          xp: 10000
```

> 1–8 级完整默认表见 §9.1；上面只展开 1/2/8 作示例。

- 同步 `uSkyBlock/config.yml`（服务器实际读取的配置）；`version` 112 → 113；
- 默认资源 `uSkyBlock-Core/src/main/resources/config.yml` 也加一份；
- 默认 `enabled: false` 更安全，确认竞技场后再在运行时开；
- 本系统依赖自定义末地世界：`end.enabled` 必须为 true，否则 `WorldManager.getEndWorld()` 不会创建/返回世界；启动时校验，缺失则禁用并打 WARN；
- `world: ""` 时自动用玩家所在世界；如果多个世界都有 `dragon_altar`，必须显式配置；
- 难度项缺失：该级在菜单显示“未开放”，不可点。

### 12.2 `plugin.yml`

- `usb.island.enddragon`：default true，加入 `usb.use` children；
- `usb.admin.enddragon`：default op，加入 `usb.admin` / `usb.admin.*` children；
- `/is enddragon` 是 `IslandCommand` 的子命令，不需要新顶层命令；
- 可选别名 `/is ed`。

### 12.3 管理员命令

- `/usb enddragon status`：打印 active、岛、发起者、难度、龙 UUID/血量、剩余时间、respawn phase、区域是否命中；
- `/usb enddragon reset [confirm]`：强制失败清理（不退款）；
- `/usb enddragon reload`：重读 `end.dragon.*`；
- `/usb enddragon check`：检查区域、世界、`DragonBattle`、柱水晶数量、返回门位置；
- 权限 `usb.admin.enddragon`。

---

## 13. 代码改动清单（实现阶段）

| 文件 | 改动 |
|------|------|
| `command/island/EndDragonCommand.java` | 新增；`RequirePlayerCommand`；打开菜单；区域/权限检查 |
| `menu/EndDragonMenu.java` | 新增；`InventoryGui` |
| `menu/EndDragonConfirmMenu.java` | 新增；二次确认 |
| `enddragon/DragonFightManager.java` | 新增；核心状态机/计时/门禁/恢复 |
| `enddragon/DragonFightPersistence.java` | 新增；末地 World PDC 的四个 active 字段 |
| `enddragon/DragonIslandData.java` | 新增；岛屿中心区块 PDC 的两个进度字段 |
| `enddragon/DragonDifficulty.java` | 新增；难度 record + 配置 |
| `enddragon/EndDragonConfig.java` | 新增；读 `end.dragon.*` |
| `event/EndDragonEvents.java` | 新增；伤害/死亡/可选 gateway/quit |
| `handler/WorldGuardHandler.java` | 新增 `getRegion(World,String)` / `isInRegion(Location,String)` |
| `command/IslandCommand.java` | 注入并 `add(endDragonCommand)` |
| `command/AdminCommand.java` | 注入并 `add(endDragonAdminCommand)` |
| `bootstrap/Listeners.java` | 按 `Settings.end_enabled` 注册 `EndDragonEvents` |
| `bootstrap/Services.java` | `DragonFightManager.startup()` / `shutdown()` |
| `Settings.java` / `EndDragonConfig` | 读开关/世界/区域/超时 |
| `config.yml`（默认 + 运行时） | `end.dragon` 节；version 113 |
| `plugin.yml` | 权限 |
| `END-DESIGN.md` / `uSkyBlock/handbook.yml` | 批准后同步口径 |
| `END-P0-CHECKLIST.md` 或新 checklist | 增加本系统 P0 项 |

---

## 14. 测试 / 验收

### 14.1 单元测试

- 消耗表：1→4 … 8→512；计数/扣除；不足；
- 四字段 active：initiator/difficulty/uuid/lastDamageTick 的读写与重启恢复；
- 伤害门禁：发起者/成员/非成员/弹射物/环境伤害；
- 奖励：岛屿 `_reward_dragon_level` 的读写、背包空间模拟、领取后归零；
- 时间：`world.getGameTime()` 的 3600s 超时；停机时间不前进。

### 14.2 服务器 P0（必须先做）

1. 祭坛世界 `getEnderDragonBattle()` 非 null；`dragon_altar` region 能命中；
2. 首龙正常击杀后 `hasBeenPreviouslyKilled=true`；`getRespawnPhase()=NONE`；
3. `initiateRespawn(4 个仪式水晶)` 和 `initiateRespawn(emptyList)` 都能跑完 `START → … → END`；`getHealingCrystals()` 是否为 10；
4. 如果祭坛在 `world_acidisland_the_end`：确认那 10 根柱是原版结构、能被 `DragonBattle` 识别；若完全不认柱，优先把祭坛/竞技场放到原生 `world_the_end`，**不写自定义补水晶逻辑**；
5. `dragon.setHealth(0)` 失败清理后能否立刻再次 `initiateRespawn`；是否生成龙蛋/返回门/折跃门/掉落；`setDroppedExp(0)`/清 drops 是否有效；
6. 重启中（`SUMMONING` / `FIGHTING`）龙和 `DragonBattle` 状态是否保留；插件恢复是否正确；
7. 伤害门禁：龙部件、箭、其他玩家、TNT、环境伤害；
8. 超时：临时 10s 配置跑一遍失败清理；
9. 成功 → `_reward_dragon_level` → 背包满不丢失 → 清理背包领取 → 归零；重启后再领；
10. Bossbar 可见范围；旁观者是否看到；
11. WorldGuard：龙破坏方块、水晶爆炸、`__global__` BUILD DENY 对战斗的影响；
12. 区块 ticket 是否让无人值守也能保持龙/计时。

### 14.3 服务器 P1

- 正常 8 级消耗/奖励；
- 并发开始；
- 岛删除；
- 管理员 reset；
- `/usb reload`；
- 首龙未杀时的拒绝提示。

---

## 15. 风险与待确认

| ID | 事项 | 建议 |
|----|------|------|
| Q1–Q4 | 已确认：祭坛在自定义末地主城；首龙已杀；奖励默认表见 §9.1；领取任何地方 | 见开头“已确认的决策” |
| Q5 | 停机是否计入 3600s | 不计入：用游戏 tick，停机时游戏时间不前进 |
| Q6 | 龙蛋/折跃门 | 首杀给龙蛋；重复击杀是否要拦 gateway / 补龙蛋，需要定 |
| Q7 | 难度机制 | 先只做 cost/血量/伤害/奖励缩放；特殊机制用 `DragonModifier` 后续加 |
| Q8 | 非参与者破坏柱水晶 | 建议拦；默认拦 |
| Q9 | 发起者能否主动放弃 | 建议可以（二次确认），避免等 3600s |
| Q10 | 成员是否也能领奖励 | 奖励记在岛上，任何岛屿成员都可以领取；如要限定发起者需要额外记录 initiator |
| Q11 | 战斗世界是 `world_acidisland_the_end` 时，10 柱能否被原版识别 | P0 确认；不行就把祭坛放原生 `world_the_end`，不写补水晶逻辑 |
| Q12 | 龙破坏主城方块 | 建议在祭坛区域拦 `EntityChangeBlockEvent`/爆炸；默认拦 |

---

## 16. 分阶段实施

- **P0（不写业务代码）**：用临时命令/调试代码验证 §14.2 的 1–6；重点确认 `initiateRespawn` 能直接跑通原版 10 柱流程，以及失败 reset 方案；
- **P1**：`EndDragonConfig` + `DragonFightPersistence` + `DragonFightManager` 状态机 + `WorldGuardHandler` 区域判定 + `/is enddragon` 空菜单；
- **P2**：菜单/确认/消耗/开始/召唤/看门狗；
- **P3**：伤害门禁/超时/失败清理/成功结算；
- **P4**：岛屿奖励标记/领取/背包空间检查/重启恢复；
- **P5**：难度缩放/管理员命令/文档/权限；
- **P6**：服务器实测（短超时、低消耗测试配置）→ 上线。

---

## 17. 参考

- [Paper `DragonBattle` Javadoc](https://jd.papermc.io/paper/26.2/org/bukkit/boss/DragonBattle.html) — `initiateRespawn(Collection)`、`getHealingCrystals()`、`getRespawnPhase()`；
- [Paper `World#getEnderDragonBattle()` Javadoc](https://jd.papermc.io/paper/26.2/org/bukkit/World.html#getEnderDragonBattle()) — 任何 `THE_END` 环境都返回 `DragonBattle`；
- Paper `CraftDragonBattle` 源码 — `initiateRespawn(Collection)` 要求 `hasBeenPreviouslyKilled() && getRespawnPhase() == NONE`，空集合表示不可取消；
- [Minecraft Wiki: Ender Dragon](https://minecraft.wiki/w/Ender_Dragon) — 复活流程、10 根柱、首杀龙蛋、折跃门；
- [`END-DESIGN.md`](END-DESIGN.md) — 现有末地世界/区域/传送门设计；
- [`END-P0-CHECKLIST.md`](END-P0-CHECKLIST.md) — 现有末地 P0 实测清单。

---

## 18. 实现状态（2026-11-08）

| 位置 | 内容 |
|------|------|
| `enddragon/DragonFightManager.java` | 计时、伤害门禁、开始/失败/成功/领取、重启恢复、区块 ticket |
| `enddragon/DragonFightPersistence.java` | 末地 World PDC 的四个 active 字段 |
| `enddragon/DragonIslandData.java` | 岛屿中心区块 PDC 的 `_max_dragon_level` / `_reward_dragon_level` |
| `enddragon/EndDragonConfig.java` | `end.dragon.*` 配置；8 级难度与奖励 |
| `enddragon/EndDragonMenu.java` / `EndDragonConfirmMenu.java` | 27 格菜单与二次确认 |
| `enddragon/EndDragonCommand.java` | `/is enddragon` / `/is ed` |
| `enddragon/EndDragonAdminCommand.java` | `/usb enddragon status|reset|reload|check` |
| `enddragon/EndDragonEvents.java` | 龙/柱水晶伤害门禁、死亡结算、祭坛保护 |
| `handler/WorldGuardHandler.java` | `getRegion(World,String)` / `isInRegion(Location,String)` |
| `config.yml`（默认 + 运行时） | `end.dragon` 节；version 112 → 113 |
| `plugin.yml` | `usb.island.enddragon` / `usb.admin.enddragon` |

**已实现的行为**：区域判定、8 级菜单与逐级解锁、4→512 水晶扣除、原版 `initiateRespawn` 复活、发起者/岛屿成员伤害限制、游戏 tick 的 3600s 超时失败、岛屿 `_reward_dragon_level` 奖励、背包满保留、重启恢复、管理员状态/重置/检查。

**仍未完成/需实测**：

- §14.2 的 P0（尤其：Paper 26.2 自定义末地世界 `getEnderDragonBattle()` 是否返回、`initiateRespawn` 是否能跑完 10 柱流程、失败时 `setRespawnPhase(END)` 的清理行为、重启恢复）。
- `dragon_altar` region 需要服主在 `world_acidisland_the_end` 中创建；代码不会自动建区域。
- 折跃门（gateway）未做额外拦截（它是方块，不能通过实体事件拦截）；如需清理要另做方块层处理。
- 难度特殊机制（额外技能/阶段）未实现，当前 1–8 级只做血量/伤害/奖励缩放。
