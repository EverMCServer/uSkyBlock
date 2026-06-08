package us.talabrek.ultimateskyblock.event;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SpawnEggMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.handler.WorldGuardHandler;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.*;

import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;
import static java.lang.Math.abs;
import static org.bukkit.Bukkit.getServer;


class TrialSpawnerConversion implements Runnable {
    private final uSkyBlock plugin;
    private final Location location;
    private final EntityType entityType;

    public TrialSpawnerConversion(uSkyBlock plugin, Location location, EntityType entityType) {
        this.plugin = plugin;
        this.location = location;
        this.entityType = entityType;
    }

    static private String getEntityConfig(EntityType entityType) {
        return switch (entityType) {
            case ZOMBIE -> "{normal_config:'minecraft:trial_chamber/melee/zombie/normal',ominous_config:'minecraft:trial_chamber/melee/zombie/ominous'}";
            case SLIME -> "{normal_config:'minecraft:trial_chamber/small_melee/slime/normal',ominous_config:'minecraft:trial_chamber/small_melee/slime/ominous'}";
            case SPIDER -> "{normal_config:'minecraft:trial_chamber/melee/spider/normal',ominous_config:'minecraft:trial_chamber/melee/spider/ominous'}";
            case SKELETON -> "{normal_config:'minecraft:trial_chamber/ranged/skeleton/normal',ominous_config:'minecraft:trial_chamber/ranged/skeleton/ominous'}";
            case BREEZE -> "{normal_config:'minecraft:trial_chamber/breeze/normal',ominous_config:'minecraft:trial_chamber/breeze/ominous'}";            // default case for other entities
            default ->
                "{normal_config:'minecraft:trial_chamber/melee/zombie/normal',ominous_config:'minecraft:trial_chamber/melee/zombie/ominous'}";
        };
    }

    @Override
    public void run() {
        Block block = location.getBlock();
        if (block.getType() != Material.TRIAL_SPAWNER) {
            return; // Only convert if the block is still a trial spawner
        }

        String data_str = getEntityConfig(entityType);

        String command_str = String.format("execute in %s run setblock %d %d %d minecraft:trial_spawner%s replace",
            block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), data_str);

        plugin.getLogger().info("Converting TrialSpawner: " + entityType);
        plugin.getLogger().info("CMD = " + command_str);
        getServer().dispatchCommand(getServer().getConsoleSender(), command_str);
    }
}
/**
 * Responsible for controlling spawns on uSkyBlock islands.
 */
@Singleton
public class SpawnEvents implements Listener {
    private static final Set<Action> RIGHT_CLICKS = Set.of(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK);

    private final uSkyBlock plugin;

    private boolean phantomsInOverworld;
    private boolean phantomsInNether;

    private static final Map<Location, Integer> conduits = new HashMap<>();
    private static final Random random = new Random();

    @Inject
    public SpawnEvents(@NotNull uSkyBlock plugin) {
        this.plugin = plugin;
        phantomsInOverworld = plugin.getConfig().getBoolean("options.spawning.phantoms.overworld", true);
        phantomsInNether = plugin.getConfig().getBoolean("options.spawning.phantoms.nether", false);
    }

    private static boolean isWaterBlock(Material material) {
        return material == Material.WATER || material == Material.BUBBLE_COLUMN;
    }

    private static boolean isPrismarineBlock(Material material) {
        return switch (material) {
            case PRISMARINE, PRISMARINE_SLAB, PRISMARINE_STAIRS, PRISMARINE_WALL,
                 PRISMARINE_BRICKS, PRISMARINE_BRICK_SLAB, PRISMARINE_BRICK_STAIRS,
                 DARK_PRISMARINE, DARK_PRISMARINE_SLAB, DARK_PRISMARINE_STAIRS,
                 SEA_LANTERN -> true;
            default -> false;
        };
    }

    /*
        当含水且未激活的潮涌核心上方的方块是流动水、且其水位改变时，潮涌核心会获得“充能”，充能层数等于水位改变的层数。
        注意，必须是水位改变，不能是水直接被替换，比如被活塞推
        充能达到20时，潮涌核心会清空充能，在9x9x9范围内随机选择8个位置，各按以下的规则尝试生成1个守卫者。
        1. 检查此位置的方块为水方块(水、流动水或气泡柱)。如果不满足条件，则放弃生成。
        2. 设置基础生成概率p=0.2。
        3. 在此位置的6个方向检查，是否此方向最远9格内第一个非水方块是海晶石类方块(包括海晶石、暗海晶石、海晶石砖及海晶灯)。每有一个方向满足条件，则p增加0.05。
        4. 以p概率在此位置生成一个守卫者。
        5. 每次最多生成3个守卫者。如果在下雨、且不是冻洋，则上限增加至5个。
        当守卫者被满级的潮涌核心击杀时，有0.1%概率在原地生成一个远古守卫者。
        守卫者和远古守卫者的战利品掉落与原版相同。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWaterLevelChanged(FluidLevelChangeEvent event) {
        if (!plugin.getWorldManager().isSkyAssociatedWorld(event.getBlock().getWorld())) {
            return;
        }
        Block conduit = event.getBlock().getRelative(BlockFace.DOWN);

        if (conduit.getType() != Material.CONDUIT) {
            return;
        }
        if (!(conduit.getBlockData() instanceof Waterlogged waterlogged) || !waterlogged.isWaterlogged()) {
            return;
        }
        if (!(conduit.getState() instanceof Conduit conduitState) || conduitState.isActive()) {
            return;
        }
        Block water = event.getBlock();
        if (water.getType() != Material.WATER) {
            return;
        }
        BlockData newdata = event.getNewData();
        if (newdata instanceof Levelled levelled) {
            // 参考文档：level 0为水源，1-7逐渐降低；8-15表示falling water，减去8为其上面一格的level
            int oldLevel = ((Levelled) water.getBlockData()).getLevel();
            if (oldLevel >= 8) oldLevel = 0;
            int newLevel = levelled.getLevel();
            if (newLevel >= 8) newLevel = 0;
            if (newLevel == oldLevel) {
                return;
            }
            int delta = abs(oldLevel - newLevel);
            Location loc = conduit.getLocation();
            int currentCharge = conduits.getOrDefault(loc, 0);
            currentCharge += delta;
//            plugin.getLogger().info(String.format("Conduit at (%d,%d,%d): level %d->%d, charge %d->%d.",
//                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
//                oldLevel, newLevel,
//                currentCharge - delta, currentCharge));
            if (currentCharge >= 30) {
                loc.getWorld().playSound(loc, Sound.BLOCK_CONDUIT_ACTIVATE, 1.0f, 1.0f);
                currentCharge -= 30;
                conduitTrySummonGuardian(conduit);
            } else {
                loc.getWorld().playSound(loc, Sound.BLOCK_CONDUIT_AMBIENT, 1.0f, 1.0f);
            }
            conduits.put(loc, currentCharge);
        }
    }

    private void conduitTrySummonGuardian(Block conduit) {
        Location loc = conduit.getLocation().clone().add(0.5, 0.5, 0.5);
        IslandInfo ii = plugin.getIslandInfo(loc);
        if (!plugin.getLimitLogic().canSpawn(EntityType.GUARDIAN, ii)) {
            return;
        }
        int maxGuardians = 2;
        if (loc.getWorld().hasStorm() && loc.getWorld().getBiome(loc) != Biome.FROZEN_OCEAN){
            maxGuardians = 5;
        }
        int spawned = 0;
        for (int i = 0; i < 5; i++) {
            Location randomLoc = loc.clone().add(
                random.nextInt(9) - 4,
                random.nextInt(9) - 4,
                random.nextInt(9) - 4
            );
            if (!IslandBorderEvent.isBothTrusted(ii, plugin.getIslandInfo(randomLoc))) {
                continue;
            }
            if (locationTrySummonGuardian(randomLoc)) {
                spawned++;
                if (spawned >= maxGuardians) {
                    break;
                }
            }
        }
    }

    // Returns true if a guardian was summoned
    private boolean locationTrySummonGuardian(Location loc) {
        if (!isWaterBlock(loc.getBlock().getType()) || !isWaterBlock(loc.getBlock().getRelative(BlockFace.DOWN).getType())) {
            return false;
        }
        double p = 0.2;
        for (BlockFace face : List.of(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            Block checkBlock = loc.getBlock();
            for (int i = 0; i < 9; i++) {
                checkBlock = checkBlock.getRelative(face);
                if (!isWaterBlock(checkBlock.getType())) {
                    if (isPrismarineBlock(checkBlock.getType())) {
                        p += 0.05;
                    }
                    break;
                }
            }
        }
        if (random.nextDouble() < p) {
            loc.getWorld().spawnEntity(loc, EntityType.GUARDIAN);
            return true;
        } else {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPiglinConvert(EntityPickupItemEvent event) {
        if (!plugin.getWorldManager().isSkyAssociatedWorld((event.getEntity().getWorld()))) {
            return;
        }
        if (event.getEntity() instanceof Piglin piglin && piglin.isAdult()) { //对玩家
            if (event.getItem().getItemStack().getType() == Material.GOLDEN_AXE) {
                // Spawn a Piglin Brute instead of a Piglin
                Location location = piglin.getLocation();
                piglin.remove();
                location.getWorld().spawnEntity(location, EntityType.PIGLIN_BRUTE);
            }
        }
    }

    @EventHandler
    public void onSpawnEggEvent(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.useItemInHand() == Event.Result.DENY || !plugin.getWorldManager().isSkyAssociatedWorld(player.getWorld())) {
            return; // Bail out, we don't care
        }

        ItemStack item = event.getItem();
        if (RIGHT_CLICKS.contains(event.getAction()) && item != null && item.getItemMeta() instanceof SpawnEggMeta) {
            if (!player.hasPermission("usb.mod.bypassprotection") && !player.isOp()) {
                if (!plugin.playerIsOnIsland(player)) {
                    event.setCancelled(true);
                    plugin.notifyPlayer(player, tr("\u00a7eYou can only use spawn-eggs on your own island."));
                    return;
                }
            }

            Block block = event.getClickedBlock();
            // If used on a trial spawner, only allow certain entities to be spawned
            if (block != null && block.getType() == Material.TRIAL_SPAWNER) {
                EntityType entityType = getSpawnEggType(item);
                if (!allowTrialSpawner(entityType)) {
                    plugin.notifyPlayer(player, tr("\u00a7cYou cannot spawn this with a trial spawner."));
                    event.setUseItemInHand(Event.Result.DENY);
                    event.setUseInteractedBlock(Event.Result.DENY);
                } else {
                    // TODO: use paper api instead of /execute command
                    Bukkit.getScheduler().runTaskLater(plugin, new TrialSpawnerConversion(plugin, block.getLocation(), entityType), 1L);
                }
            } else {
                // No limit checks for spawner eggs now.
                return;
            }
        }
    }

    private boolean allowTrialSpawner(EntityType entityType) {
        if (entityType == null) {
            return false;
        }
        return switch (entityType) {
            case ZOMBIE, SLIME, SPIDER, SKELETON, BREEZE -> true;
            default -> false;
        };
    }

    private static @Nullable EntityType getSpawnEggType(@NotNull ItemStack itemStack) {
        if (itemStack.getItemMeta() instanceof SpawnEggMeta spawnEggMeta) {
            // getSpawnedEntity is broken
            EntitySnapshot spawnedEntity = null; // spawnEggMeta.getSpawnedEntity();
            if (spawnedEntity != null) {
                return spawnedEntity.getEntityType();
            } else {
                String key = itemStack.getType().getKey().toString();
                String entityKey = key.replace("_spawn_egg", "");
                NamespacedKey namespacedKey = Objects.requireNonNull(NamespacedKey.fromString(entityKey));
                return Registry.ENTITY_TYPE.get(namespacedKey);
            }
        } else {
            return null;
        }
    }

    private static boolean isReasonBypassLimit(CreatureSpawnEvent.SpawnReason reason) {
        return switch (reason) {
            case SPAWNER_EGG, LIGHTNING, SLIME_SPLIT, BUILD_WITHER, INFECTION, TRIAL_SPAWNER, TRAP, ENDER_PEARL, COMMAND, CUSTOM -> true;
            default -> false;
        };
    }
    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event == null || !plugin.getWorldManager().isSkyAssociatedWorld(event.getLocation().getWorld())) {
            return; // Bail out, we don't care
        }
        if (isReasonBypassLimit(event.getSpawnReason())) {
            return; // Allow it, no limit checks
        }
        checkLimits(event, event.getEntity().getType(), event.getLocation());
    }

    private boolean isDeepOceanBiome(Location loc) {
        Collection<Biome> deepOceans = Set.of(Biome.DEEP_OCEAN, Biome.DEEP_COLD_OCEAN, Biome.DEEP_FROZEN_OCEAN, Biome.DEEP_LUKEWARM_OCEAN);
        return deepOceans.contains(loc.getWorld().getBiome(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
    }

    private void checkLimits(Cancellable event, EntityType entityType, Location location) {
        if (entityType == null) {
            return; // Only happens on "other-plugins", i.e. EchoPet
        }
        String islandName = WorldGuardHandler.getIslandNameAt(location);
        if (islandName == null) {
            event.setCancelled(true); // Only allow spawning on active islands...
            return;
        }
        IslandInfo islandInfo = plugin.getIslandInfo(islandName);
        if (islandInfo == null) {
            // Disallow spawns on inactive islands
            event.setCancelled(true);
            return;
        }
        if (!plugin.getLimitLogic().canSpawn(entityType, islandInfo)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPhantomSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Phantom) ||
            event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }

        World spawnWorld = event.getEntity().getWorld();
        if (!phantomsInOverworld && plugin.getWorldManager().isSkyWorld(spawnWorld)) {
            event.setCancelled(true);
        }

        if (!phantomsInNether && plugin.getWorldManager().isSkyNether(spawnWorld)) {
            event.setCancelled(true);
        }
    }

    /**
     * Changes the setting that allows Phantoms to spawn in the overworld. Used for testing purposes.
     *
     * @param state True/enabled means spawning is allowed, false disallowed.
     */
    void setPhantomsInOverworld(boolean state) {
        this.phantomsInOverworld = state;
    }

    /**
     * Changes the setting that allows Phantoms to spawn in the nether. Used for testing purposes.
     *
     * @param state True/enabled means spawning is allowed, false disallowed.
     */
    void setPhantomsInNether(boolean state) {
        this.phantomsInNether = state;
    }
}
