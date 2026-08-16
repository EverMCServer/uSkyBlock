package us.talabrek.ultimateskyblock.progress;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;
import us.talabrek.ultimateskyblock.util.Scheduler;

import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * An abstraction for storing island progress in YAML files.
 * This provides a way to track game progress and support content creation.
 * <p>Progress is shared between all members of an island: any member's progress
 * resolves to the <b>island leader's</b> progress file ({@code progress/&lt;leaderUUID&gt;.yml}),
 * mirroring how challenge completions are stored per island.
 * <p><b>This class is not thread-safe.</b> Only call it from the main server thread.
 * Writes are batched — entries are flushed periodically and on shutdown/quit.
 */
@Singleton
public class ProgressLogic {

    private static final String PROGRESS_DIR = "progress";

    private final uSkyBlock plugin;
    private final Logger logger;
    private final File progressDir;
    private final Map<UUID, PlayerProgress> cache = new HashMap<>();

    @Inject
    public ProgressLogic(@NotNull uSkyBlock plugin, @NotNull Logger logger, @NotNull Scheduler scheduler) {
        this.plugin = plugin;
        this.logger = logger;
        this.progressDir = new File(plugin.getDataFolder(), PROGRESS_DIR);
        if (!progressDir.exists() && !progressDir.mkdirs()) {
            logger.severe("Failed to create progress directory: " + progressDir.getAbsolutePath());
        }
        long saveEverySeconds = Math.max(1, plugin.getConfig().getLong("options.advanced.progress.saveEvery", 60L));
        scheduler.sync(this::flushDirty, Duration.ofSeconds(saveEverySeconds), Duration.ofSeconds(saveEverySeconds));
    }

    /**
     * Retrieves the progress for a player. If the player is not the leader of their
     * island, the leader's progress is returned instead (island-shared progress).
     *
     * @param player The player to retrieve progress for.
     * @return The island progress for the player.
     */
    public PlayerProgress getProgress(@NotNull Player player) {
        return getProgress(player.getUniqueId());
    }

    /**
     * Retrieves the progress for a player UUID. If the UUID does not belong to the
     * island leader, the leader's progress is returned instead (island-shared progress).
     * Players without an island fall back to their own UUID.
     *
     * @param playerUUID The UUID of the player to retrieve progress for.
     * @return The island progress for the player.
     */
    public PlayerProgress getProgress(@NotNull UUID playerUUID) {
        PlayerInfo playerInfo = plugin.getPlayerInfo(playerUUID);
        if (playerInfo != null && playerInfo.getHasIsland()) {
            IslandInfo islandInfo = plugin.getIslandInfo(playerInfo);
            if (islandInfo != null) {
                UUID leaderUUID = islandInfo.getLeaderUniqueId();
                if (leaderUUID != null && !playerUUID.equals(leaderUUID)) {
                    playerUUID = leaderUUID;
                }
            }
        }
        return cache.computeIfAbsent(playerUUID,
            uuid -> new PlayerProgress(uuid, new File(progressDir, uuid + ".yml"), logger));
    }

    /**
     * Adds the given value to the current and total progress for the given key of the
     * player's island. This is the API for content code to accumulate progress.
     *
     * @param player The player triggering the accumulation.
     * @param key    The key for the progress to modify.
     * @param value  The value to add to the progress.
     * @return The new current value of the progress.
     */
    public double addToProgress(@NotNull Player player, String key, double value) {
        return getProgress(player).addToProgress(key, value);
    }

    /**
     * Migrates the player's progress to their island leader's progress.
     * <p>Current progress overwrites the leader's value per key; total progress is added.
     * The original entry is reset afterwards.
     * <p>This is called when the leader of an island changes, so that the new leader's
     * progress file carries the island's accumulated progress.
     *
     * @param originalLeaderUUID The UUID of the original leader whose progress will be migrated.
     */
    public void migrateProgress(@NotNull UUID originalLeaderUUID) {
        PlayerInfo playerInfo = plugin.getPlayerInfo(originalLeaderUUID);
        IslandInfo island = playerInfo != null ? plugin.getIslandInfo(playerInfo) : null;
        if (island == null) {
            return;
        }
        UUID leaderUUID = island.getLeaderUniqueId();
        if (leaderUUID == null) {
            return;
        }
        PlayerProgress leaderProgress = getProgress(leaderUUID);
        PlayerProgress progress = cache.get(originalLeaderUUID);
        if (progress == null || leaderProgress == progress) {
            logger.warning("Tried to migrate progress for player " + originalLeaderUUID + " but there is nothing to migrate.");
            return;
        }
        leaderProgress.mergeFrom(progress);
        progress.reset();
        progress.flush();
        logger.info("Migrated progress for player " + originalLeaderUUID);
    }

    /**
     * Flushes and removes a player's progress entry from the cache. Called when a player logs out.
     *
     * @param player The player to remove from cache.
     */
    public void clearCache(@NotNull Player player) {
        PlayerProgress progress = cache.remove(player.getUniqueId());
        if (progress != null) {
            progress.flush();
        }
    }

    /**
     * Flushes all dirty progress entries to disk.
     */
    public void flushDirty() {
        for (PlayerProgress progress : cache.values()) {
            progress.flush();
        }
    }

    /**
     * Flushes all dirty entries. Called on plugin shutdown.
     */
    public void shutdown() {
        flushDirty();
    }
}
