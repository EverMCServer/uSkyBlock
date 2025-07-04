package us.talabrek.ultimateskyblock.world;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import dk.lockfuglsang.minecraft.util.TimeUtil;
import org.apache.commons.lang3.Validate;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import us.talabrek.ultimateskyblock.PluginConfig;
import us.talabrek.ultimateskyblock.Settings;
import us.talabrek.ultimateskyblock.handler.AsyncWorldEditHandler;
import us.talabrek.ultimateskyblock.hook.HookManager;
import us.talabrek.ultimateskyblock.uSkyBlock;
import us.talabrek.ultimateskyblock.util.LocationUtil;
import us.talabrek.ultimateskyblock.util.Scheduler;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import static us.talabrek.ultimateskyblock.Settings.island_height;

@Singleton
public class WorldManager {
    private final Path schematicPath;
    private final HookManager hookManager;
    private final PluginConfig config;
    private final Scheduler scheduler;
    private final Logger logger;

    public static volatile World skyBlockWorld;
    public static volatile World skyBlockNetherWorld;

    static {
        skyBlockWorld = null;
    }

    @Inject
    public WorldManager(
        @NotNull uSkyBlock plugin,
        @NotNull Logger logger,
        @NotNull PluginConfig config,
        @NotNull HookManager hookManager,
        @NotNull Scheduler scheduler
    ) {
        this.schematicPath = plugin.getDataFolder().toPath().resolve("schematics").resolve("spawn.schem");
        this.hookManager = hookManager;
        this.config = config;
        this.logger = logger;
        this.scheduler = scheduler;
    }

    /**
     * Get the {@link ChunkRegenerator} for the given {@link World}.
     *
     * @param world World to get the ChunkRegenerator for.
     * @return ChunkRegenerator for the given world.
     */
    @NotNull
    public ChunkRegenerator getChunkRegenerator(@NotNull World world) {
        return new ChunkRegenerator(world);
    }

    /**
     * Removes all unnamed {@link Monster}'s at the given {@link Location}.
     *
     * @param target Location to remove unnamed monsters.
     */
    public void removeCreatures(@Nullable final Location target) {
        if (!Settings.island_removeCreaturesByTeleport || target == null || target.getWorld() == null) {
            return;
        }

        final int px = target.getBlockX();
        final int py = target.getBlockY();
        final int pz = target.getBlockZ();
        for (int x = -1; x <= 1; ++x) {
            for (int z = -1; z <= 1; ++z) {
                Chunk chunk = target.getWorld().getChunkAt(
                    new Location(target.getWorld(), (px + x * 16), py, (pz + z * 16)));

                Arrays.stream(chunk.getEntities())
                    .filter(entity -> entity instanceof Monster)
                    .filter(entity -> entity.getCustomName() == null)
                    .forEach(Entity::remove);
            }
        }
    }

    /**
     * Sets the spawn location for the given {@link World} if currently unset. Creates a safe spawn location if
     * necessary by calling {@link WorldManager#createSpawn(Location)}.
     *
     * @param world        World to setup.
     * @param islandHeight Height at which islands will be created.
     */
    private void setupWorld(@NotNull World world, int islandHeight) {
        Validate.notNull(world, "World cannot be null");

        if (!config.getYamlConfig().getBoolean("options.advanced.manageSpawn")) {
            return;
        }

        if (LocationUtil.isEmptyLocation(world.getSpawnLocation())) {
            world.setSpawnLocation(0, islandHeight, 0);
        }

        Location spawnLocation = world.getSpawnLocation();
        if (!LocationUtil.isSafeLocation(spawnLocation)) {
            // Warn the user why we're doing this, because it's a FAQ on the forums:
            logger.warning("Spawn location in " + world.getName() + " is considered unsafe. " +
                "Placing default spawn. This check can be disabled in config.yml, option manageSpawn.");
            createSpawn(spawnLocation);
        }
    }

    /**
     * Creates the world spawn at the given {@link Location}. Places the spawn schematic if
     * configured and when it exists on disk. Places a gold block with two air above it otherwise.
     *
     * @param spawnLocation Location to create the spawn at.
     */
    private void createSpawn(@NotNull Location spawnLocation) {
        Validate.notNull(spawnLocation, "SpawnLocation cannot be null");
        Validate.notNull(spawnLocation.getWorld(), "SpawnLocation#world cannot be null");

        World world = spawnLocation.getWorld();

        if (config.getYamlConfig().getInt("options.general.spawnSize", 0) > 32 && Files.exists(schematicPath)) {
            AsyncWorldEditHandler.loadIslandSchematic(schematicPath.toFile(), spawnLocation, null);
        } else {
            Block spawnBlock = world.getBlockAt(spawnLocation).getRelative(BlockFace.DOWN);
            spawnBlock.setType(Material.GOLD_BLOCK);
            Block air = spawnBlock.getRelative(BlockFace.UP);
            air.setType(Material.AIR);
            air.getRelative(BlockFace.UP).setType(Material.AIR);
        }
    }

    /**
     * Gets the {@link ChunkGenerator} responsible for generating chunks in the overworld skyworld.
     *
     * @return ChunkGenerator for overworld skyworld.
     */
    @NotNull
    private ChunkGenerator getOverworldGenerator() {
        try {
            String clazz = config.getYamlConfig().getString("options.advanced.chunk-generator",
                "us.talabrek.ultimateskyblock.world.AcidIslandChunkGenerator");
            Object generator = Class.forName(clazz).getDeclaredConstructor().newInstance();
            if (generator instanceof ChunkGenerator) {
                return (ChunkGenerator) generator;
            }
        } catch (ClassNotFoundException ex) {
            logger.log(Level.WARNING, "Invalid overworld chunk-generator configured: " + ex);
        } catch (IllegalAccessException | InstantiationException | InvocationTargetException |
                 NoSuchMethodException ex) {
            logger.log(Level.WARNING, "Unable to instantiate overworld chunk-generator: " + ex);
        }
        return new AcidIslandChunkGenerator();
    }

    /**
     * Gets the {@link ChunkGenerator} responsible for generating chunks in the nether skyworld.
     *
     * @return ChunkGenerator for nether skyworld.
     */
    @NotNull
    private ChunkGenerator getNetherGenerator() {
        try {
            String clazz = config.getYamlConfig().getString("nether.chunk-generator",
                "us.talabrek.ultimateskyblock.world.AcidNetherChunkGenerator");
            Object generator = Class.forName(clazz).getDeclaredConstructor().newInstance();
            if (generator instanceof ChunkGenerator) {
                return (ChunkGenerator) generator;
            }
        } catch (ClassNotFoundException ex) {
            logger.log(Level.WARNING, "Invalid nether chunk-generator configured: " + ex);
        } catch (IllegalAccessException | InstantiationException | InvocationTargetException |
                 NoSuchMethodException ex) {
            logger.log(Level.WARNING, "Unable to instantiate nether chunk-generator: " + ex);
        }
        return new AcidNetherChunkGenerator();
    }

    /**
     * Gets a {@link ChunkGenerator} for use in a default world, as specified in the server configuration
     *
     * @param worldName Name of the world that this will be applied to
     * @param id        Unique ID, if any, that was specified to indicate which generator was requested
     * @return ChunkGenerator for use in the default world generation
     */
    @Nullable
    public ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        Validate.notNull(worldName, "WorldName cannot be null");

        return ((id != null && id.endsWith("nether")) || (worldName.endsWith("nether")))
            && Settings.nether_enabled
            ? getNetherGenerator()
            : getOverworldGenerator();
    }

    /**
     * Gets the skyblock island {@link World}. Creates and/or imports the world if necessary.
     *
     * @return Skyblock island world.
     */
    @NotNull
    public synchronized World getWorld() {
        if (skyBlockWorld == null) {
            skyBlockWorld = Bukkit.getWorld(Settings.general_worldName);
            ChunkGenerator skyGenerator = getOverworldGenerator();
            ChunkGenerator worldGenerator = skyBlockWorld != null ? skyBlockWorld.getGenerator() : null;
            if (skyBlockWorld == null
                || skyBlockWorld.canGenerateStructures()
                || worldGenerator == null
                || !worldGenerator.getClass().getName().equals(skyGenerator.getClass().getName())) {
                skyBlockWorld = WorldCreator
                    .name(Settings.general_worldName)
                    .type(WorldType.NORMAL)
                    .generateStructures(false)
                    .environment(World.Environment.NORMAL)
                    .generator(skyGenerator)
                    .createWorld();
                skyBlockWorld.save();
            }

            scheduler.sync(() ->
                hookManager.getMultiverse().ifPresent(hook -> {
                    hook.registerOverworld(skyBlockWorld);
                    setupWorld(skyBlockWorld, Settings.island_height);
                }), TimeUtil.ticksAsDuration(config.getYamlConfig().getLong("init.initDelay", 50L) + 40L));
        }

        return skyBlockWorld;
    }

    /**
     * Gets the skyblock nether island {@link World}. Creates and/or imports the world if necessary. Returns null if
     * the nether is not enabled in the plugin configuration.
     *
     * @return Skyblock nether island world, or null if nether is disabled.
     */
    @Nullable
    public synchronized World getNetherWorld() {
        if (!Settings.nether_enabled) {
            return null;
        }

        if (skyBlockNetherWorld == null) {
            skyBlockNetherWorld = Bukkit.getWorld(Settings.general_worldName + "_nether");
            ChunkGenerator skyGenerator = getNetherGenerator();
            ChunkGenerator worldGenerator = skyBlockNetherWorld != null ? skyBlockNetherWorld.getGenerator() : null;
            if (skyBlockNetherWorld == null
                || skyBlockNetherWorld.canGenerateStructures()
                || worldGenerator == null
                || !worldGenerator.getClass().getName().equals(skyGenerator.getClass().getName())) {
                skyBlockNetherWorld = WorldCreator
                    .name(Settings.general_worldName + "_nether")
                    .type(WorldType.NORMAL)
                    .generateStructures(false)
                    .environment(World.Environment.NETHER)
                    .generator(skyGenerator)
                    .createWorld();
                skyBlockNetherWorld.save();
            }

            scheduler.sync(() ->
                hookManager.getMultiverse().ifPresent(hook -> {
                    hook.registerNetherworld(skyBlockNetherWorld);
                    setupWorld(skyBlockNetherWorld, island_height / 2);
                }), TimeUtil.ticksAsDuration(config.getYamlConfig().getLong("init.initDelay", 50L) + 100L));
        }

        return skyBlockNetherWorld;
    }

    /**
     * Checks if the given {@link World} is the skyblock island world.
     *
     * @param world World to check.
     * @return True if the given world is the skyblock island world, false otherwise.
     */
    public boolean isSkyWorld(@Nullable World world) {
        if (world == null) {
            return false;
        }

        return getWorld().getName().equalsIgnoreCase(world.getName());
    }

    /**
     * Checks if the given {@link World} is the skyblock nether island world.
     *
     * @param world World to check.
     * @return True if the given world is the skyblock nether island world, false otherwise.
     */
    public boolean isSkyNether(@Nullable World world) {
        if (world == null) {
            return false;
        }

        World netherWorld = getNetherWorld();
        return netherWorld != null && world.getName().equalsIgnoreCase(netherWorld.getName());
    }

    /**
     * Checks if the given {@link World} is associated with Ultimate Skyblock.
     *
     * @param world World to check.
     * @return True if the given world is associated with the plugin, false otherwise.
     */
    public boolean isSkyAssociatedWorld(@Nullable World world) {
        if (world == null) {
            return false;
        }

        return world.getName().startsWith(WorldManager.skyBlockWorld.getName())
            && !(world.getEnvironment() == World.Environment.NETHER && !Settings.nether_enabled)
            && !(world.getEnvironment() == World.Environment.THE_END);
    }
}
