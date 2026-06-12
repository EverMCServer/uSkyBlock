package us.talabrek.ultimateskyblock.hook.world;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.jetbrains.annotations.NotNull;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.world.MultiverseWorld;
import org.mvplugins.multiverse.core.world.WorldManager;
import org.mvplugins.multiverse.core.world.options.ImportWorldOptions;
import org.mvplugins.multiverse.external.vavr.control.Option;
import org.mvplugins.multiverse.inventories.MultiverseInventoriesApi;
import org.mvplugins.multiverse.inventories.profile.group.WorldGroup;
import org.mvplugins.multiverse.inventories.profile.group.WorldGroupManager;
import org.mvplugins.multiverse.inventories.share.Sharables;
import us.talabrek.ultimateskyblock.Settings;
import us.talabrek.ultimateskyblock.hook.PluginHook;
import us.talabrek.ultimateskyblock.uSkyBlock;
import us.talabrek.ultimateskyblock.util.LocationUtil;

import java.util.Optional;
import java.util.logging.Level;

public class MultiverseHook extends PluginHook {
    private MultiverseCoreApi coreApi;
    private MultiverseInventoriesApi inventoriesApi;

    private static final String GENERATOR_NAME = "uSkyBlock";

    public MultiverseHook(@NotNull uSkyBlock plugin) {
        super(plugin, "Multiverse", "Multiverse");

        if (plugin.getServer().getPluginManager().isPluginEnabled("Multiverse-Core")) {
            setupCore().ifPresent(api -> this.coreApi = api);
        }
        if (plugin.getServer().getPluginManager().isPluginEnabled("Multiverse-Inventories")) {
            setupInventories().ifPresent(api -> this.inventoriesApi = api);
        }
    }

    private Optional<MultiverseCoreApi> setupCore() {
        try {
            if (MultiverseCoreApi.isLoaded()) {
                MultiverseCoreApi api = MultiverseCoreApi.get();
                plugin.getLogger().info("Found Multiverse-Core.");
                return Optional.of(api);
            }
            plugin.getLogger().warning("Multiverse-Core API not yet loaded.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to access Multiverse-Core API.", e);
        }
        return Optional.empty();
    }

    private Optional<MultiverseInventoriesApi> setupInventories() {
        try {
            MultiverseInventoriesApi api = MultiverseInventoriesApi.get();
            plugin.getLogger().info("Found Multiverse-Inventories.");
            return Optional.of(api);
        } catch (IllegalStateException e) {
            plugin.getLogger().warning("Multiverse-Inventories API not yet loaded.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to access Multiverse-Inventories API.", e);
        }
        return Optional.empty();
    }

    /**
     * Registers the given {@link World} to Multiverse-Core as the skyblock overworld (skyworld).
     * @param world World to register.
     */
    public void registerOverworld(@NotNull World world) {
        if (coreApi == null) {
            return;
        }

        WorldManager wm = coreApi.getWorldManager();
        String worldName = world.getName();

        if (!wm.isWorld(worldName)) {
            wm.importWorld(ImportWorldOptions.worldName(worldName)
                .environment(World.Environment.NORMAL)
                .generator(GENERATOR_NAME)
                .useSpawnAdjust(false));
        }

        Option<MultiverseWorld> optWorld = wm.getWorld(worldName);
        if (optWorld.isEmpty()) {
            return;
        }
        MultiverseWorld mvWorld = optWorld.get();

        mvWorld.setScale(1.0);

        if (Settings.general_spawnSize > 0 && LocationUtil.isEmptyLocation(mvWorld.getSpawnLocation())) {
            Location spawn = LocationUtil.centerOnBlock(
                new Location(world, 0.5, Settings.island_height + 0.1, 0.5));
            mvWorld.setAdjustSpawn(false);
            mvWorld.setSpawnLocation(spawn);
            world.setSpawnLocation(spawn);
        }

        if (!Settings.extras_sendToSpawn) {
            mvWorld.setRespawnWorld(mvWorld.getName());
        }
    }

    /**
     * Registers the given {@link World} to Multiverse-Core as the skyblock nether world (skyworld_nether).
     * @param world World to register.
     */
    public void registerNetherworld(@NotNull World world) {
        if (coreApi == null) {
            return;
        }

        WorldManager wm = coreApi.getWorldManager();
        String worldName = world.getName();

        if (!wm.isWorld(worldName)) {
            wm.importWorld(ImportWorldOptions.worldName(worldName)
                .environment(World.Environment.NETHER)
                .generator(GENERATOR_NAME)
                .useSpawnAdjust(false));
        }

        Option<MultiverseWorld> optWorld = wm.getWorld(worldName);
        if (optWorld.isEmpty()) {
            return;
        }
        MultiverseWorld mvWorld = optWorld.get();

        mvWorld.setScale(1.0);

        if (Settings.general_spawnSize > 0 && LocationUtil.isEmptyLocation(mvWorld.getSpawnLocation())) {
            Location spawn = LocationUtil.centerOnBlock(
                new Location(world, 0.5, Settings.island_height / 2.0 + 0.1, 0.5));
            mvWorld.setAdjustSpawn(false);
            mvWorld.setSpawnLocation(spawn);
            world.setSpawnLocation(spawn);
        }

        if (!Settings.extras_sendToSpawn) {
            mvWorld.setRespawnWorld(plugin.getWorldManager().getWorld().getName());
        }

        linkNetherInventory(plugin.getWorldManager().getWorld(), world);
    }

    private void linkNetherInventory(@NotNull World... worlds) {
        if (coreApi == null || inventoriesApi == null) {
            return;
        }

        WorldGroupManager groupManager = inventoriesApi.getWorldGroupManager();
        WorldGroup worldGroup = groupManager.getGroup("skyblock");
        if (worldGroup == null) {
            worldGroup = groupManager.newEmptyGroup("skyblock");
            worldGroup.getShares().addAll(Sharables.ALL_DEFAULT);
        }
        for (World world : worlds) {
            worldGroup.addWorld(world);
        }
        groupManager.updateGroup(worldGroup);
    }
}
