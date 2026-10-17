package us.talabrek.ultimateskyblock.island.level;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dk.lockfuglsang.minecraft.file.FileUtil;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.PluginConfig;
import us.talabrek.ultimateskyblock.Settings;
import us.talabrek.ultimateskyblock.api.async.Callback;
import us.talabrek.ultimateskyblock.handler.WorldGuardHandler;
import us.talabrek.ultimateskyblock.island.task.ChunkSnapShotTask;
import us.talabrek.ultimateskyblock.uSkyBlock;
import us.talabrek.ultimateskyblock.util.Scheduler;
import us.talabrek.ultimateskyblock.world.WorldManager;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Business logic regarding the calculation of level
 */
@Singleton
public class ChunkSnapshotLevelLogic extends CommonLevelLogic {

    private final Plugin plugin;
    private final PluginConfig pluginConfig;
    private final Scheduler scheduler;
    private final Logger logger;

    @Inject
    public ChunkSnapshotLevelLogic(
        @NotNull uSkyBlock plugin,
        @NotNull WorldManager worldManager,
        @NotNull PluginConfig pluginConfig,
        @NotNull Scheduler scheduler,
        @NotNull Logger logger
    ) {
        super(FileUtil.getYmlConfiguration("levelConfig.yml"), worldManager);
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    /**
     * Small functional interface used to keep the (deeply nested) snapshot tasks readable.
     */
    @FunctionalInterface
    private interface SnapshotCallback {
        void accept(ProtectedRegion region, List<ChunkSnapshot> overworld, ProtectedRegion netherRegion,
                    List<ChunkSnapshot> nether, ProtectedRegion endRegion, List<ChunkSnapshot> end);
    }

    @Override
    public void calculateScoreAsync(final Location l, final Callback<IslandScore> callback) {
        // TODO: 10/05/2015 - R4zorax: Ensure no overlapping calls to this one happen...
        logger.entering(this.getClass().getName(), "calculateScoreAsync");
        // is further threading needed here?
        final ProtectedRegion region = WorldGuardHandler.getIslandRegionAt(l);
        if (region == null) {
            return;
        }
        final Location netherLoc = getNetherLocation(l);
        final Location endLoc = getEndLocation(l);
        final ProtectedRegion netherRegion = WorldGuardHandler.getNetherRegionAt(netherLoc);
        final ProtectedRegion endRegion = WorldGuardHandler.getEndRegionAt(endLoc);
        final SnapshotCallback finalizer = (reg, overworld, nr, nether, er, end) ->
            calculateAsync(reg, overworld, nr, nether, er, end, callback);
        new ChunkSnapShotTask(scheduler, pluginConfig, l, region, new Callback<>() {
            @Override
            public void run() {
                snapshotNether(getState());
            }

            private void snapshotNether(List<ChunkSnapshot> snapshotsOverworld) {
                if (netherLoc == null) {
                    snapshotEnd(snapshotsOverworld, null);
                    return;
                }
                new ChunkSnapShotTask(scheduler, pluginConfig, netherLoc, netherRegion, new Callback<>() {
                    @Override
                    public void run() {
                        snapshotEnd(snapshotsOverworld, getState());
                    }
                }).runTask(plugin);
            }

            private void snapshotEnd(List<ChunkSnapshot> snapshotsOverworld, List<ChunkSnapshot> snapshotsNether) {
                if (endLoc == null) {
                    finalizer.accept(region, snapshotsOverworld, netherRegion, snapshotsNether, null, null);
                    return;
                }
                new ChunkSnapShotTask(scheduler, pluginConfig, endLoc, endRegion, new Callback<>() {
                    @Override
                    public void run() {
                        finalizer.accept(region, snapshotsOverworld, netherRegion, snapshotsNether, endRegion, getState());
                    }
                }).runTask(plugin);
            }
        }).runTask(plugin);
    }

    private void calculateAsync(ProtectedRegion region, List<ChunkSnapshot> snapshotsOverworld,
                                ProtectedRegion netherRegion, List<ChunkSnapshot> snapshotsNether,
                                ProtectedRegion endRegion, List<ChunkSnapshot> snapshotsEnd,
                                Callback<IslandScore> callback) {
        new BukkitRunnable() {
            @Override
            public void run() {
                calculateScoreAndCallback(region, snapshotsOverworld, netherRegion, snapshotsNether, endRegion, snapshotsEnd, callback);
            }
        }.runTaskAsynchronously(plugin);
    }

    private void calculateScoreAndCallback(ProtectedRegion region, List<ChunkSnapshot> snapshotsOverworld, ProtectedRegion netherRegion, List<ChunkSnapshot> snapshotsNether, ProtectedRegion endRegion, List<ChunkSnapshot> snapshotsEnd, Callback<IslandScore> callback) {
        IslandScore islandScore = calculateScore(region, snapshotsOverworld, netherRegion, snapshotsNether, endRegion, snapshotsEnd);
        callback.setState(islandScore);
        scheduler.sync(callback);
        logger.exiting(this.getClass().getName(), "calculateScoreAsync");
    }

    private IslandScore calculateScore(ProtectedRegion region, List<ChunkSnapshot> snapshotsOverworld, ProtectedRegion netherRegion, List<ChunkSnapshot> snapshotsNether, ProtectedRegion endRegion, List<ChunkSnapshot> snapshotsEnd) {
        final BlockCountCollection counts = new BlockCountCollection(scoreMap);
        addRegionBlocks(counts, region, snapshotsOverworld);
        IslandScore islandScore = createIslandScore(counts);
        if (islandScore.getScore() >= activateNetherAtLevel && netherRegion != null && snapshotsNether != null) {
            // Add nether levels
            addRegionBlocks(counts, netherRegion, snapshotsNether);
            islandScore = createIslandScore(counts);
        }
        if (islandScore.getScore() >= Settings.end_activateAtLevel && endRegion != null && snapshotsEnd != null) {
            // Add end levels
            addRegionBlocks(counts, endRegion, snapshotsEnd);
            islandScore = createIslandScore(counts);
        }
        return islandScore;
    }

    /**
     * Adds all non-air blocks inside the given region to the block count collection, using the (already captured)
     * chunk snapshots of the region's world.
     *
     * @param counts    Collection to add the blocks to.
     * @param region    Region to scan.
     * @param snapshots Chunk snapshots of the region's world.
     */
    private void addRegionBlocks(BlockCountCollection counts, ProtectedRegion region, List<ChunkSnapshot> snapshots) {
        int minX = region.getMinimumPoint().getBlockX();
        int maxX = region.getMaximumPoint().getBlockX();
        int minY = region.getMinimumPoint().getBlockY();
        int maxY = region.getMaximumPoint().getBlockY();
        int minZ = region.getMinimumPoint().getBlockZ();
        int maxZ = region.getMaximumPoint().getBlockZ();

        for (int x = minX; x <= maxX; ++x) {
            for (int z = minZ; z <= maxZ; ++z) {
                ChunkSnapshot chunk = getChunkSnapshot(x >> 4, z >> 4, snapshots);
                if (chunk == null) {
                    // This should NOT happen!
                    logger.log(Level.WARNING, "Missing chunk in snapshot for x,z = " + x + "," + z);
                    continue;
                }
                int cx = (x & 0xf);
                int cz = (z & 0xf);
                for (int y = minY; y < maxY; y++) {
                    Material blockType = chunk.getBlockType(cx, y, cz);
                    if (blockType == Material.AIR) {
                        continue;
                    }
                    counts.add(blockType);
                }
            }
        }
    }

    private static ChunkSnapshot getChunkSnapshot(int x, int z, List<ChunkSnapshot> snapshots) {
        for (ChunkSnapshot chunk : snapshots) {
            if (chunk.getX() == x && chunk.getZ() == z) {
                return chunk;
            }
        }
        return null;
    }
}
