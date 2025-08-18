package us.talabrek.ultimateskyblock.event;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SpawnEggMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import us.talabrek.ultimateskyblock.api.IslandInfo;
import us.talabrek.ultimateskyblock.handler.WorldGuardHandler;
import us.talabrek.ultimateskyblock.uSkyBlock;
import us.talabrek.ultimateskyblock.util.LocationUtil;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;
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
            // TODO: Update these after 1.21.4(?)
            case ZOMBIE -> "{normal_config: {simultaneous_mobs: 3.0f, simultaneous_mobs_added_per_player: 0.5f, ticks_between_spawn: 20, spawn_potentials: [{data: {entity: {id: \"minecraft:zombie\"}}, weight: 1}]}, spawn_data: {entity: {id: \"minecraft:zombie\"}}, id: \"minecraft:trial_spawner\", ominous_config: {loot_tables_to_eject: [{data: \"minecraft:spawners/ominous/trial_chamber/key\", weight: 3}, {data: \"minecraft:spawners/ominous/trial_chamber/consumables\", weight: 7}], simultaneous_mobs: 3.0f, simultaneous_mobs_added_per_player: 0.5f, ticks_between_spawn: 20, spawn_potentials: [{data: {equipment: {slot_drop_chances: 0.0f, loot_table: \"minecraft:equipment/trial_chamber_melee\"}, entity: {id: \"minecraft:zombie\"}}, weight: 1}]}}";
            case SLIME -> "{normal_config: {simultaneous_mobs: 3.0f, simultaneous_mobs_added_per_player: 0.5f, ticks_between_spawn: 20, spawn_potentials: [{data: {entity: {Size: 1, id: \"minecraft:slime\"}}, weight: 3}, {data: {entity: {Size: 2, id: \"minecraft:slime\"}}, weight: 1}]}, spawn_data: {entity: {Size: 1, id: \"minecraft:slime\"}}, id: \"minecraft:trial_spawner\", ominous_config: {loot_tables_to_eject: [{data: \"minecraft:spawners/ominous/trial_chamber/key\", weight: 3}, {data: \"minecraft:spawners/ominous/trial_chamber/consumables\", weight: 7}], simultaneous_mobs: 4.0f, simultaneous_mobs_added_per_player: 0.5f, ticks_between_spawn: 20, spawn_potentials: [{data: {entity: {Size: 1, id: \"minecraft:slime\"}}, weight: 3}, {data: {entity: {Size: 2, id: \"minecraft:slime\"}}, weight: 1}], total_mobs: 12.0f}}";
            case SPIDER -> "{normal_config: {simultaneous_mobs: 3.0f, simultaneous_mobs_added_per_player: 0.5f, ticks_between_spawn: 20, spawn_potentials: [{data: {entity: {id: \"minecraft:spider\"}}, weight: 1}]}, spawn_data: {entity: {id: \"minecraft:spider\"}}, id: \"minecraft:trial_spawner\", ominous_config: {loot_tables_to_eject: [{data: \"minecraft:spawners/ominous/trial_chamber/key\", weight: 3}, {data: \"minecraft:spawners/ominous/trial_chamber/consumables\", weight: 7}], simultaneous_mobs: 4.0f, simultaneous_mobs_added_per_player: 0.5f, ticks_between_spawn: 20, spawn_potentials: [{data: {entity: {id: \"minecraft:spider\"}}, weight: 1}], total_mobs: 12.0f}}";
            case SKELETON -> "{normal_config: {simultaneous_mobs: 3.0f, simultaneous_mobs_added_per_player: 0.5f, ticks_between_spawn: 20, spawn_potentials: [{data: {entity: {id: \"minecraft:skeleton\"}}, weight: 1}]}, spawn_data: {entity: {id: \"minecraft:skeleton\"}}, id: \"minecraft:trial_spawner\", ominous_config: {loot_tables_to_eject: [{data: \"minecraft:spawners/ominous/trial_chamber/key\", weight: 3}, {data: \"minecraft:spawners/ominous/trial_chamber/consumables\", weight: 7}], simultaneous_mobs: 3.0f, simultaneous_mobs_added_per_player: 0.5f, ticks_between_spawn: 20, spawn_potentials: [{data: {equipment: {slot_drop_chances: 0.0f, loot_table: \"minecraft:equipment/trial_chamber_ranged\"}, entity: {id: \"minecraft:skeleton\"}}, weight: 1}]}}";
            case BREEZE -> "{normal_config: {simultaneous_mobs: 1.0f, simultaneous_mobs_added_per_player: 0.5f, ticks_between_spawn: 20, total_mobs_added_per_player: 1.0f, spawn_potentials: [{data: {entity: {id: \"minecraft:breeze\"}}, weight: 1}], total_mobs: 2.0f}, spawn_data: {entity: {id: \"minecraft:breeze\"}}, id: \"minecraft:trial_spawner\", ominous_config: {loot_tables_to_eject: [{data: \"minecraft:spawners/ominous/trial_chamber/key\", weight: 3}, {data: \"minecraft:spawners/ominous/trial_chamber/consumables\", weight: 7}], simultaneous_mobs_added_per_player: 0.5f, ticks_between_spawn: 20, total_mobs_added_per_player: 1.0f, spawn_potentials: [{data: {entity: {id: \"minecraft:breeze\"}}, weight: 1}], total_mobs: 4.0f}}";
            // default case for other entities
            default -> "{normal_config: {simultaneous_mobs: 3.0f, simultaneous_mobs_added_per_player: 0.5f, ticks_between_spawn: 20, spawn_potentials: [{data: {entity: {id: \"minecraft:zombie\"}}, weight: 1}]}, spawn_data: {entity: {id: \"minecraft:zombie\"}}, id: \"minecraft:trial_spawner\", ominous_config: {loot_tables_to_eject: [{data: \"minecraft:spawners/ominous/trial_chamber/key\", weight: 3}, {data: \"minecraft:spawners/ominous/trial_chamber/consumables\", weight: 7}], simultaneous_mobs: 3.0f, simultaneous_mobs_added_per_player: 0.5f, ticks_between_spawn: 20, spawn_potentials: [{data: {equipment: {slot_drop_chances: 0.0f, loot_table: \"minecraft:equipment/trial_chamber_melee\"}, entity: {id: \"minecraft:zombie\"}}, weight: 1}]}}";
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

    @Inject
    public SpawnEvents(@NotNull uSkyBlock plugin) {
        this.plugin = plugin;
        phantomsInOverworld = plugin.getConfig().getBoolean("options.spawning.phantoms.overworld", true);
        phantomsInNether = plugin.getConfig().getBoolean("options.spawning.phantoms.nether", false);
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
                    Bukkit.getScheduler().runTaskLater(plugin, new TrialSpawnerConversion(plugin, block.getLocation(), entityType), 1L);
                }
            } else {
                // regular spawn egg, check limits
                checkLimits(event, getSpawnEggType(item), player.getLocation());
                if (event.useItemInHand() == Event.Result.DENY) {
                    plugin.notifyPlayer(player, tr("\u00a7cYou have reached your spawn-limit for your island."));
                    event.setUseItemInHand(Event.Result.DENY);
                    event.setUseInteractedBlock(Event.Result.DENY);
                }
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

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event == null || !plugin.getWorldManager().isSkyAssociatedWorld(event.getLocation().getWorld())) {
            return; // Bail out, we don't care
        }
        if (event.getSpawnReason().equals(CreatureSpawnEvent.SpawnReason.SPAWNER_EGG)) {
            return; // Allow it, the above method would have blocked it if it should be blocked.
        }
        checkLimits(event, event.getEntity().getType(), event.getLocation());
        if (event.getEntity() instanceof WaterMob) {
            Location loc = event.getLocation();
            if (isDeepOceanBiome(loc) && isPrismarineRoof(loc)) {
                loc.getWorld().spawnEntity(loc, EntityType.GUARDIAN);
                event.setCancelled(true);
            }
        }
    }

    private boolean isPrismarineRoof(Location loc) {
        Collection<Material> prismarineBlocks = Set.of(Material.PRISMARINE, Material.PRISMARINE_BRICKS, Material.DARK_PRISMARINE);
        return prismarineBlocks.contains(LocationUtil.findRoofBlock(loc).getType());
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
