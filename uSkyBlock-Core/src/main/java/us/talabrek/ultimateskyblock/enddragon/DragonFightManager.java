package us.talabrek.ultimateskyblock.enddragon;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import us.talabrek.ultimateskyblock.handler.WorldGuardHandler;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;
import us.talabrek.ultimateskyblock.util.Scheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Repeatable ender dragon challenge.
 *
 * <p>Persisted state is intentionally tiny: the active dragon's UUID, the initiator, the difficulty and the last
 * damage tick live in the end world PDC; the island's max unlocked level and pending reward level live in the PDC of
 * the chunk containing the main-world island center.</p>
 */
@Singleton
public class DragonFightManager {
    private static final Logger log = Logger.getLogger(DragonFightManager.class.getName());
    private static final int MAX_LEVEL = 8;
    private static final int ARENA_CHUNK_RADIUS = 3;
    private static final int ARENA_PROTECTION_RADIUS = 128;
    private static final int TICKS_PER_SECOND = 20;

    private final uSkyBlock plugin;
    private final EndDragonConfig config;
    private final DragonFightPersistence persistence;
    private final Scheduler scheduler;
    private final Set<Chunk> arenaChunks = new HashSet<>();

    private BukkitTask tickTask;
    private DragonFightData active;
    private boolean resetting;

    @Inject
    public DragonFightManager(@NotNull uSkyBlock plugin, @NotNull EndDragonConfig config,
                              @NotNull DragonFightPersistence persistence, @NotNull Scheduler scheduler) {
        this.plugin = plugin;
        this.config = config;
        this.persistence = persistence;
        this.scheduler = scheduler;
    }

    public void startup() {
        config.reload();
        World world = getConfiguredWorld();
        active = world != null ? persistence.loadActive(world) : null;
        if (active != null) {
            restoreActive(world);
        }
        if (tickTask == null) {
            tickTask = scheduler.sync(this::tick, Duration.ofSeconds(1), Duration.ofSeconds(1));
        }
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        releaseArenaChunks();
    }

    // ---------------------------------------------------------------------
    // Start
    // ---------------------------------------------------------------------

    public boolean start(@NotNull Player player, int level) {
        if (!config.enabled()) {
            player.sendMessage("§c[末影龙] 该功能未启用。");
            return false;
        }
        if (resetting) {
            player.sendMessage("§c[末影龙] 上一场挑战正在清理，请稍后再试。");
            return false;
        }
        if (active != null) {
            player.sendMessage("§c[末影龙] 当前已有挑战正在进行。");
            return false;
        }
        if (!isInAltar(player)) {
            player.sendMessage("§c[末影龙] 你需要站在 dragon_altar 祭坛区域内才能开始挑战。");
            return false;
        }
        IslandInfo island = getIsland(player);
        if (island == null) {
            player.sendMessage("§c[末影龙] 你需要先拥有一个岛屿。");
            return false;
        }
        int maxLevel = DragonIslandData.getMaxLevel(island);
        if (level < 1 || level > maxLevel) {
            player.sendMessage("§c[末影龙] 该难度尚未解锁。当前最高难度：§e" + maxLevel + "§c。");
            return false;
        }
        int rewardLevel = DragonIslandData.getRewardLevel(island);
        if (rewardLevel > 0) {
            player.sendMessage("§c[末影龙] 你还有未领取的难度 §e" + rewardLevel + "§c 奖励，请先领取再开始新挑战。");
            return false;
        }
        DragonDifficulty difficulty = config.difficulty(level);
        if (difficulty == null) {
            player.sendMessage("§c[末影龙] 该难度不存在。");
            return false;
        }
        World world = getConfiguredWorld();
        if (world == null) {
            player.sendMessage("§c[末影龙] 末地世界不可用。");
            return false;
        }
        DragonBattle battle = world.getEnderDragonBattle();
        if (battle == null) {
            player.sendMessage("§c[末影龙] 该世界没有可用的龙战状态。");
            return false;
        }
        if (!battle.hasBeenPreviouslyKilled()) {
            player.sendMessage("§c[末影龙] 请先正常击杀一次原版末影龙，才能开始重复挑战。");
            return false;
        }
        if (battle.getEnderDragon() != null) {
            player.sendMessage("§c[末影龙] 当前世界中已经有一条末影龙。");
            return false;
        }
        if (battle.getRespawnPhase() != DragonBattle.RespawnPhase.NONE) {
            player.sendMessage("§c[末影龙] 复活序列已经在进行中。");
            return false;
        }
        int owned = countCrystals(player);
        if (owned < difficulty.cost()) {
            player.sendMessage("§c[末影龙] 末影水晶不足：需要 §e" + difficulty.cost() + "§c 个，当前只有 §e" + owned + "§c 个。");
            return false;
        }
        int removed = takeCrystals(player, difficulty.cost());
        if (removed < difficulty.cost()) {
            refund(player, removed);
            player.sendMessage("§c[末影龙] 扣除末影水晶失败，已退回。");
            return false;
        }

        List<EnderCrystal> crystals = new ArrayList<>(4);
        try {
            loadArenaChunks(world, battle);
            removeRitualCrystalsNearPortal(world, battle);
            crystals = spawnRitualCrystals(world, battle);
            boolean started = battle.initiateRespawn(crystals);
            if (!started) {
                removeCrystals(crystals);
                releaseArenaChunks();
                refund(player, difficulty.cost());
                player.sendMessage("§c[末影龙] 复活序列启动失败，末影水晶已退回。");
                return false;
            }
        } catch (Exception e) {
            log.log(Level.SEVERE, "Unable to initiate ender dragon respawn", e);
            removeCrystals(crystals);
            releaseArenaChunks();
            refund(player, difficulty.cost());
            player.sendMessage("§c[末影龙] 复活序列启动失败，末影水晶已退回。");
            return false;
        }

        active = new DragonFightData(player.getUniqueId(), level, null, world.getGameTime());
        persistence.saveActive(world, active);
        broadcast(island.getName(), "§d[末影龙] §f" + player.getName() + " 发起了难度 §e" + level
            + "§f 的挑战（消耗 §e" + difficulty.cost() + "§f 个末影水晶）。");
        player.sendMessage("§a[末影龙] 挑战已开始，原版复活序列正在运行。");
        return true;
    }

    private List<EnderCrystal> spawnRitualCrystals(World world, DragonBattle battle) {
        Location center = battle.getEndPortalLocation();
        if (center == null) {
            center = world.getSpawnLocation();
        }
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        List<EnderCrystal> crystals = new ArrayList<>(4);
        for (int[] offset : offsets) {
            Location location = center.clone().add(offset[0], 1, offset[1]);
            if (!world.getBlockAt(location).isEmpty()) {
                location.add(0, 1, 0);
            }
            try {
                EnderCrystal crystal = world.spawn(location, EnderCrystal.class);
                crystal.setShowingBottom(false);
                crystal.setInvulnerable(true);
                crystals.add(crystal);
            } catch (Exception e) {
                log.log(Level.WARNING, "Unable to spawn ritual end crystal at " + location, e);
            }
        }
        return crystals;
    }

    // ---------------------------------------------------------------------
    // Tick / state transitions
    // ---------------------------------------------------------------------

    private void tick() {
        try {
            tickInternal();
        } catch (Exception e) {
            log.log(Level.SEVERE, "Unexpected error in ender dragon challenge tick", e);
        }
    }

    private void tickInternal() {
        if (active == null) {
            releaseArenaChunks();
            return;
        }
        if (!config.enabled()) {
            fail("功能被关闭");
            return;
        }
        World world = getConfiguredWorld();
        if (world == null) {
            fail("末地世界不可用");
            return;
        }
        DragonBattle battle = world.getEnderDragonBattle();
        if (battle == null) {
            fail("龙战状态不可用");
            return;
        }
        IslandInfo island = getIsland(active.initiator());
        if (island == null) {
            fail("岛屿已删除或不可用");
            return;
        }
        long now = world.getGameTime();
        if (active.dragonUuid() == null) {
            tickSummoning(world, battle, now);
            return;
        }
        EnderDragon dragon = battle.getEnderDragon();
        if (dragon == null) {
            fail("末影龙实体丢失");
            return;
        }
        if (!active.dragonUuid().equals(dragon.getUniqueId())) {
            fail("末影龙 UUID 不匹配");
            return;
        }
        if (now - active.lastDamageTick() >= timeoutTicks(active)) {
            fail("超过 " + timeoutSeconds(active) + " 秒没有受到有效伤害");
        }
    }

    private void tickSummoning(World world, DragonBattle battle, long now) {
        EnderDragon dragon = battle.getEnderDragon();
        if (dragon != null) {
            DragonDifficulty difficulty = config.difficulty(active.difficulty());
            if (difficulty == null) {
                fail("难度配置缺失");
                return;
            }
            applyDifficulty(dragon, difficulty);
            active = active.withDragon(dragon.getUniqueId());
            persistence.saveActive(world, active);
            broadcast(getIslandName(active.initiator()), "§a[末影龙] 难度 §e" + active.difficulty()
                + "§a 的末影龙已出现，战斗开始！");
            return;
        }
        long elapsed = now - active.lastDamageTick();
        if (elapsed > config.summoningTimeoutSeconds() * (long) TICKS_PER_SECOND) {
            failTechnical("召唤超时");
        } else if (battle.getRespawnPhase() == DragonBattle.RespawnPhase.NONE
            && elapsed > 5L * TICKS_PER_SECOND) {
            failTechnical("召唤序列中断");
        }
    }

    private void applyDifficulty(EnderDragon dragon, DragonDifficulty difficulty) {
        AttributeInstance maxHealth = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(difficulty.maxHealth());
        }
        dragon.setHealth(difficulty.maxHealth());
        dragon.customName(Component.text("末影龙 · 难度 " + difficulty.level(), NamedTextColor.LIGHT_PURPLE));
        dragon.setCustomNameVisible(true);
        dragon.setPersistent(true);
    }

    private int timeoutSeconds(DragonFightData data) {
        DragonDifficulty difficulty = config.difficulty(data.difficulty());
        return difficulty != null ? difficulty.timeoutSeconds() : config.timeoutSeconds();
    }

    private long timeoutTicks(DragonFightData data) {
        return timeoutSeconds(data) * (long) TICKS_PER_SECOND;
    }

    // ---------------------------------------------------------------------
    // Damage / death
    // ---------------------------------------------------------------------

    public boolean shouldAllowDragonDamage(@NotNull EntityDamageEvent event, @NotNull EnderDragon dragon,
                                           @Nullable Player damager) {
        if (active == null || active.dragonUuid() == null || !active.dragonUuid().equals(dragon.getUniqueId())) {
            return false;
        }
        if (damager == null || !isAllowedDamager(damager)) {
            return false;
        }
        if (event.getFinalDamage() > 0) {
            World world = getConfiguredWorld();
            if (world != null) {
                active = active.withLastDamageTick(world.getGameTime());
                persistence.saveActive(world, active);
            }
        }
        return true;
    }

    public boolean isAllowedDamager(@NotNull Player player) {
        if (active == null) {
            return false;
        }
        if (active.initiator().equals(player.getUniqueId())) {
            return true;
        }
        IslandInfo island = getIsland(active.initiator());
        return island != null && island.getMemberUUIDs().contains(player.getUniqueId());
    }

    public boolean shouldProtectCrystal(@NotNull EnderCrystal crystal, @Nullable Player damager) {
        if (active == null) {
            return false;
        }
        if (!isInArena(crystal.getLocation())) {
            return false;
        }
        return damager == null || !isAllowedDamager(damager);
    }

    public void onDragonDeath(@NotNull EnderDragon dragon, @NotNull EntityDeathEvent event) {
        if (resetting) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            return;
        }
        if (active == null || active.dragonUuid() == null || !active.dragonUuid().equals(dragon.getUniqueId())) {
            return;
        }
        if (!config.keepVanillaDrops()) {
            event.getDrops().clear();
        }
        if (!config.keepVanillaXp()) {
            event.setDroppedExp(0);
        }
        success();
    }

    private void success() {
        DragonFightData current = active;
        if (current == null) {
            return;
        }
        IslandInfo island = getIsland(current.initiator());
        World world = getConfiguredWorld();
        if (island != null) {
            int maxLevel = Math.min(MAX_LEVEL, Math.max(DragonIslandData.getMaxLevel(island), current.difficulty() + 1));
            DragonIslandData.setMaxLevel(island, maxLevel);
            DragonIslandData.setRewardLevel(island, current.difficulty());
        }
        active = null;
        persistence.saveActive(world, null);
        removeRitualCrystalsNearPortal(world, world != null ? world.getEnderDragonBattle() : null);
        releaseArenaChunks();
        String islandName = island != null ? island.getName() : "";
        broadcast(islandName, "§a[末影龙] 难度 §e" + current.difficulty() + "§a 挑战成功！"
            + "请在 §e/is enddragon §a领取奖励。");
    }

    private void failTechnical(String reason) {
        DragonFightData current = active;
        if (current != null) {
            DragonDifficulty difficulty = config.difficulty(current.difficulty());
            Player initiator = Bukkit.getPlayer(current.initiator());
            if (initiator != null && difficulty != null) {
                refund(initiator, difficulty.cost());
                initiator.sendMessage("§e[末影龙] 召唤失败，已退回 " + difficulty.cost() + " 个末影水晶。");
            }
        }
        fail(reason);
    }

    public void fail(@NotNull String reason) {
        DragonFightData current = active;
        if (current == null) {
            return;
        }
        IslandInfo island = getIsland(current.initiator());
        String islandName = island != null ? island.getName() : "";
        World world = getConfiguredWorld();
        resetting = true;
        active = null;
        persistence.saveActive(world, null);
        broadcast(islandName, "§c[末影龙] 挑战失败：" + reason);
        if (world != null) {
            DragonBattle battle = world.getEnderDragonBattle();
            if (battle != null) {
                battle.setPreviouslyKilled(true);
                if (battle.getRespawnPhase() != DragonBattle.RespawnPhase.NONE
                    && battle.getRespawnPhase() != DragonBattle.RespawnPhase.END) {
                    try {
                        battle.setRespawnPhase(DragonBattle.RespawnPhase.END);
                    } catch (Exception e) {
                        log.log(Level.FINE, "Unable to force dragon respawn phase to END", e);
                    }
                }
                EnderDragon dragon = battle.getEnderDragon();
                if (dragon != null) {
                    try {
                        dragon.setHealth(0);
                    } catch (Exception e) {
                        log.log(Level.WARNING, "Unable to kill dragon during reset", e);
                        dragon.remove();
                    }
                }
                battle.resetCrystals();
            }
            removeRitualCrystalsNearPortal(world, battle);
        }
        releaseArenaChunks();
        scheduler.sync(() -> {
            World checkWorld = getConfiguredWorld();
            if (checkWorld != null) {
                DragonBattle battle = checkWorld.getEnderDragonBattle();
                if (battle != null) {
                    EnderDragon dragon = battle.getEnderDragon();
                    if (dragon != null) {
                        dragon.remove();
                    }
                    battle.resetCrystals();
                }
                removeRitualCrystalsNearPortal(checkWorld, checkWorld.getEnderDragonBattle());
            }
            resetting = false;
        }, Duration.ofSeconds(Math.max(1, config.failCooldownSeconds())));
    }

    public void forceReset(@NotNull String reason) {
        if (active == null) {
            return;
        }
        fail("管理员重置：" + reason);
    }

    private void restoreActive(World world) {
        if (active == null) {
            return;
        }
        DragonBattle battle = world.getEnderDragonBattle();
        if (battle == null) {
            fail("龙战状态不可用");
            return;
        }
        loadArenaChunks(world, battle);
        if (getIsland(active.initiator()) == null) {
            fail("重启后岛屿不可用");
            return;
        }
        long now = world.getGameTime();
        if (active.dragonUuid() == null) {
            EnderDragon dragon = battle.getEnderDragon();
            if (dragon != null) {
                tickSummoning(world, battle, now);
            } else if (battle.getRespawnPhase() == DragonBattle.RespawnPhase.NONE) {
                failTechnical("召唤序列在重启后中断");
            } else if (now - active.lastDamageTick() > config.summoningTimeoutSeconds() * (long) TICKS_PER_SECOND) {
                failTechnical("召唤超时");
            }
            return;
        }
        EnderDragon dragon = battle.getEnderDragon();
        if (dragon == null) {
            fail("重启后末影龙实体丢失");
            return;
        }
        if (!active.dragonUuid().equals(dragon.getUniqueId())) {
            fail("重启后末影龙 UUID 不匹配");
            return;
        }
        if (now - active.lastDamageTick() >= timeoutTicks(active)) {
            fail("重启时已超过 " + timeoutSeconds(active) + " 秒无伤害");
        }
    }

    // ---------------------------------------------------------------------
    // Rewards (island chunk PDC)
    // ---------------------------------------------------------------------

    public boolean claim(@NotNull Player player) {
        IslandInfo island = getIsland(player);
        if (island == null) {
            player.sendMessage("§c[末影龙] 你需要先拥有一个岛屿。");
            return false;
        }
        int rewardLevel = DragonIslandData.getRewardLevel(island);
        if (rewardLevel <= 0) {
            player.sendMessage("§e[末影龙] 没有待领取的奖励。");
            return false;
        }
        DragonDifficulty difficulty = config.difficulty(rewardLevel);
        if (difficulty == null) {
            player.sendMessage("§c[末影龙] 奖励难度配置缺失，请联系管理员。");
            return false;
        }
        List<ItemStack> items = difficulty.createItemRewards();
        if (!canFit(player, items)) {
            player.sendMessage("§c[末影龙] 背包空间不足，奖励已保留，请清理背包后再领取。");
            return false;
        }
        HashMap<Integer, ItemStack> leftovers = player.getInventory()
            .addItem(items.toArray(new ItemStack[0]));
        if (!leftovers.isEmpty()) {
            player.sendMessage("§c[末影龙] 背包空间不足，奖励已保留，请清理背包后再领取。");
            return false;
        }
        if (difficulty.xp() > 0) {
            player.giveExp(difficulty.xp());
        }
        if (difficulty.currency() > 0) {
            plugin.getHookManager().getEconomyHook().ifPresent(hook ->
                hook.depositPlayer(player, difficulty.currency()));
        }
        for (String command : difficulty.commands()) {
            plugin.execCommand(player, command, true);
        }
        DragonIslandData.setRewardLevel(island, 0);
        player.sendMessage("§a[末影龙] 已领取难度 §e" + rewardLevel + "§a 的奖励。");
        return true;
    }

    private boolean canFit(Player player, List<ItemStack> items) {
        if (items.isEmpty()) {
            return true;
        }
        Inventory test = Bukkit.createInventory(null, 36);
        test.setStorageContents(player.getInventory().getStorageContents().clone());
        List<ItemStack> copies = items.stream().map(ItemStack::clone).toList();
        Map<Integer, ItemStack> leftovers = test.addItem(copies.toArray(new ItemStack[0]));
        return leftovers.isEmpty();
    }

    public int getMaxLevel(Player player) {
        IslandInfo island = getIsland(player);
        return island != null ? DragonIslandData.getMaxLevel(island) : 1;
    }

    public int getRewardLevel(Player player) {
        IslandInfo island = getIsland(player);
        return island != null ? DragonIslandData.getRewardLevel(island) : 0;
    }

    public boolean hasPendingRewards(Player player) {
        return getRewardLevel(player) > 0;
    }

    // ---------------------------------------------------------------------
    // Items / chunks / region
    // ---------------------------------------------------------------------

    private int countCrystals(Player player) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == Material.END_CRYSTAL) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private int takeCrystals(Player player, int amount) {
        int remaining = amount;
        int removed = 0;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != Material.END_CRYSTAL) {
                continue;
            }
            int take = Math.min(item.getAmount(), remaining);
            item.setAmount(item.getAmount() - take);
            if (item.getAmount() <= 0) {
                contents[i] = null;
            }
            remaining -= take;
            removed += take;
        }
        player.getInventory().setStorageContents(contents);
        player.updateInventory();
        return removed;
    }

    private void refund(Player player, int amount) {
        if (amount <= 0) {
            return;
        }
        HashMap<Integer, ItemStack> leftovers = player.getInventory()
            .addItem(crystalStacks(amount).toArray(new ItemStack[0]));
        if (!leftovers.isEmpty()) {
            log.warning("Unable to refund " + amount + " end crystals to " + player.getName()
                + " (inventory full); leftovers were not dropped.");
        }
    }

    private static List<ItemStack> crystalStacks(int amount) {
        List<ItemStack> stacks = new ArrayList<>();
        int remaining = amount;
        while (remaining > 0) {
            int take = Math.min(64, remaining);
            stacks.add(new ItemStack(Material.END_CRYSTAL, take));
            remaining -= take;
        }
        return stacks;
    }

    private void removeCrystals(List<EnderCrystal> crystals) {
        for (EnderCrystal crystal : crystals) {
            if (crystal != null && !crystal.isDead()) {
                crystal.remove();
            }
        }
    }

    private void removeRitualCrystalsNearPortal(World world, @Nullable DragonBattle battle) {
        if (world == null) {
            return;
        }
        Location center = battle != null ? battle.getEndPortalLocation() : null;
        if (center == null) {
            center = world.getSpawnLocation();
        }
        if (center.getWorld() == null) {
            return;
        }
        for (EnderCrystal crystal : world.getEntitiesByClass(EnderCrystal.class)) {
            if (crystal.getLocation().getWorld() != null
                && crystal.getLocation().getWorld().equals(center.getWorld())
                && crystal.getLocation().distanceSquared(center) <= 8.0 * 8.0) {
                crystal.remove();
            }
        }
    }

    private void loadArenaChunks(World world, DragonBattle battle) {
        if (!config.keepArenaLoaded()) {
            return;
        }
        Location center = battle.getEndPortalLocation();
        if (center == null) {
            center = world.getSpawnLocation();
        }
        int chunkX = center.getBlockX() >> 4;
        int chunkZ = center.getBlockZ() >> 4;
        for (int dx = -ARENA_CHUNK_RADIUS; dx <= ARENA_CHUNK_RADIUS; dx++) {
            for (int dz = -ARENA_CHUNK_RADIUS; dz <= ARENA_CHUNK_RADIUS; dz++) {
                Chunk chunk = world.getChunkAt(chunkX + dx, chunkZ + dz);
                if (arenaChunks.add(chunk)) {
                    world.addPluginChunkTicket(chunk.getX(), chunk.getZ(), plugin);
                }
            }
        }
    }

    private void releaseArenaChunks() {
        for (Chunk chunk : arenaChunks) {
            try {
                chunk.getWorld().removePluginChunkTicket(chunk.getX(), chunk.getZ(), plugin);
            } catch (Exception e) {
                log.log(Level.FINE, "Unable to remove chunk ticket", e);
            }
        }
        arenaChunks.clear();
    }

    private boolean isInArena(Location location) {
        if (location.getWorld() == null || active == null) {
            return false;
        }
        World world = getConfiguredWorld();
        if (world == null) {
            return false;
        }
        return isInArena(location, world, world.getEnderDragonBattle());
    }

    private boolean isInArena(Location location, World world, @Nullable DragonBattle battle) {
        if (location.getWorld() == null) {
            return false;
        }
        if (WorldGuardHandler.isInRegion(location, config.altarRegion())) {
            return true;
        }
        if (world == null || !world.equals(location.getWorld())) {
            return false;
        }
        Location center = battle != null ? battle.getEndPortalLocation() : null;
        if (center == null) {
            center = world.getSpawnLocation();
        }
        return center.getWorld() != null && center.getWorld().equals(location.getWorld())
            && center.distanceSquared(location) <= (double) ARENA_PROTECTION_RADIUS * ARENA_PROTECTION_RADIUS;
    }

    // ---------------------------------------------------------------------
    // Lookup / status
    // ---------------------------------------------------------------------

    public boolean isInAltar(@NotNull Player player) {
        if (!config.enabled()) {
            return false;
        }
        World world = player.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            return false;
        }
        if (!config.worldName().isBlank() && !world.getName().equalsIgnoreCase(config.worldName())) {
            return false;
        }
        return WorldGuardHandler.isInRegion(player.getLocation(), config.altarRegion());
    }

    @Nullable
    public DragonFightData getActive() {
        return active;
    }

    public boolean hasActive() {
        return active != null;
    }

    @Nullable
    public Double getDragonHealth() {
        if (active == null || active.dragonUuid() == null) {
            return null;
        }
        World world = getConfiguredWorld();
        if (world == null) {
            return null;
        }
        DragonBattle battle = world.getEnderDragonBattle();
        if (battle == null || battle.getEnderDragon() == null) {
            return null;
        }
        return battle.getEnderDragon().getHealth();
    }

    public double getActiveDamageMultiplier() {
        if (active == null) {
            return 1.0;
        }
        DragonDifficulty difficulty = config.difficulty(active.difficulty());
        return difficulty != null ? difficulty.damageMultiplier() : 1.0;
    }

    public long getRemainingSeconds() {
        if (active == null) {
            return 0L;
        }
        World world = getConfiguredWorld();
        if (world == null) {
            return 0L;
        }
        long timeout = active.dragonUuid() == null
            ? config.summoningTimeoutSeconds() * (long) TICKS_PER_SECOND
            : timeoutTicks(active);
        long end = active.lastDamageTick() + timeout;
        return Math.max(0L, (end - world.getGameTime()) / TICKS_PER_SECOND);
    }

    public boolean isInArenaForProtection(Location location) {
        return isInArena(location);
    }

    @NotNull
    private IslandInfo getIsland(Player player) {
        IslandInfo island = plugin.getIslandInfo(player);
        return island != null && island.exists() && !island.getLeader().isEmpty() ? island : null;
    }

    @Nullable
    private IslandInfo getIsland(UUID playerId) {
        PlayerInfo playerInfo = plugin.getPlayerInfo(playerId);
        if (playerInfo == null || !playerInfo.getHasIsland()) {
            return null;
        }
        IslandInfo island = plugin.getIslandInfo(playerInfo);
        return island != null && island.exists() && !island.getLeader().isEmpty() ? island : null;
    }

    @NotNull
    private String getIslandName(UUID playerId) {
        IslandInfo island = getIsland(playerId);
        return island != null ? island.getName() : "";
    }

    @Nullable
    private World getConfiguredWorld() {
        String name = config.worldName();
        World world = name != null && !name.isBlank() ? Bukkit.getWorld(name) : null;
        if (world == null) {
            world = plugin.getWorldManager().getEndWorld();
        }
        return world;
    }

    private void broadcast(String islandName, String message) {
        if (islandName == null || islandName.isBlank()) {
            return;
        }
        IslandInfo island = plugin.getIslandInfo(islandName);
        if (island != null) {
            island.sendMessageToOnlineMembers(message);
        }
    }

    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        if (active == null) {
            lines.add("§e[末影龙] 当前没有进行中的挑战。");
            return lines;
        }
        World world = getConfiguredWorld();
        lines.add("§e[末影龙] 发起者: §f" + active.initiator());
        lines.add("§e[末影龙] 难度: §f" + active.difficulty());
        lines.add("§e[末影龙] 龙 UUID: §f" + active.dragonUuid());
        lines.add("§e[末影龙] 最后伤害 tick: §f" + active.lastDamageTick()
            + " §7(当前 " + (world != null ? world.getGameTime() : -1) + ")");
        lines.add("§e[末影龙] 剩余: §f" + getRemainingSeconds() + "s");
        lines.add("§e[末影龙] 清理中: §f" + resetting);
        return lines;
    }
}
