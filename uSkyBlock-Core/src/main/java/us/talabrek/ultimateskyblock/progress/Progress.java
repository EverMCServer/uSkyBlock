package us.talabrek.ultimateskyblock.progress;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import us.talabrek.ultimateskyblock.event.ProgressEvents;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * An abstraction for storing player progress in YAML files.
 * This provides a way to track game progress and support content creation.
 * <p><b>This mechanism is currently not thread-safe.</b>
 */
public class Progress {

    private static uSkyBlock plugin;
    private static Logger logger;
    private static File progressDir;
    // Global cache for player progress (UUID-based for internal consistency)
    private static final Map<UUID, Progress> uuidCache = new HashMap<>();
    // Legacy cache for backwards compatibility
    private static final Map<Player, Progress> playerCache = new HashMap<>();

    /**
     * Initialize the Progress tracking mechanism.
     * This is called by the uSkyBlock plugin on startup.
     * @param plugin The uSkyBlock plugin instance.
     */
    public static void init(uSkyBlock plugin) {
        Progress.plugin = plugin;
        Progress.logger = plugin.getLogger();
        Progress.progressDir = new File(plugin.getDataFolder(), "progress");
        if (!progressDir.exists()) {
            if (!progressDir.mkdirs()) {
                logger.severe("Failed to create progress directory: " + progressDir.getAbsolutePath());
            }
        }
        plugin.getServer().getPluginManager().registerEvents(new ProgressEvents(), plugin);
    }

    /**
     * Retrieves the progress for a player by UUID. If the player's progress is not already
     * cached, it will be created and stored in the cache. Note that if the player is
     * not the leader of their party, the leader's progress will be returned instead.
     * @param playerUUID The UUID of the player to retrieve progress for.
     * @return The cached progress for the player.
     */
    public static Progress getProgress(UUID playerUUID) {
        PlayerInfo playerInfo = plugin.getPlayerInfo(playerUUID);
        IslandInfo islandInfo = plugin.getIslandInfo(playerInfo);
        UUID leaderUUID = islandInfo.getLeaderUniqueId(); // Use getLeaderUniqueId() directly
        if (!playerUUID.equals(leaderUUID)) {
            playerUUID = leaderUUID;
        }
        return forceGetProgressUUID(playerUUID);
    }

    /**
     * Retrieves the progress for a player. If the player's progress is not already
     * cached, it will be created and stored in the cache. Note that if the player is
     * not the leader of their party, the leader's progress will be returned instead.
     * @param player The player to retrieve progress for.
     * @return The cached progress for the player.
     */
    public static Progress getProgress(Player player) {
        return getProgress(player.getUniqueId());
    }

    /**
     * Retrieves the progress for a player by UUID. If the player's progress is not already
     * cached, it will be created and stored in the cache. Note that this method
     * will return a new progress object even if the player is not the leader of
     * their party. If you want to get the progress for the leader of the player's
     * party, use {@link #getProgress(UUID)}.
     * @param playerUUID The UUID of the player to retrieve progress for.
     * @return The cached progress for the player.
     */
    public static Progress forceGetProgressUUID(UUID playerUUID) {
        if (uuidCache.containsKey(playerUUID)) {
            return uuidCache.get(playerUUID);
        } else {
            Progress progress = new Progress(playerUUID);
            uuidCache.put(playerUUID, progress);
            return progress;
        }
    }

    /**
     * Retrieves the progress for a player. If the player's progress is not already
     * cached, it will be created and stored in the cache. Note that this method
     * will return a new progress object even if the player is not the leader of
     * their party. If you want to get the progress for the leader of the player's
     * party, use {@link #getProgress(Player)}.
     * @param player The player to retrieve progress for.
     * @return The cached progress for the player.
     */
    public static Progress forceGetProgressPlayer(Player player) {
        UUID playerUUID = player.getUniqueId();
        Progress progress = forceGetProgressUUID(playerUUID);

        // Update legacy cache for backwards compatibility
        playerCache.put(player, progress);

        return progress;
    }

    /**
     * Migrates the player's progress to their island leader's progress by UUID.
     * <p>
     * This method retrieves the player's island leader and migrates each progress entry
     * from the player's progress to the leader's progress. The leader's progress for each key
     * is incremented by the player's current progress for that key.
     * <p>
     * This is useful in scenarios where a player joins an island led by another player, and
     * they assume leadership of the island, migrating island progress to their progress file.
     *
     * @param playerUUID The UUID of the player whose progress will be migrated to the leader's progress.
     */
    public static void migrateProgress(UUID playerUUID) {
        PlayerInfo playerInfo = plugin.getPlayerInfo(playerUUID);
        IslandInfo island = plugin.getIslandInfo(playerInfo);
        UUID leaderUUID = island.getLeaderUniqueId(); // Use getLeaderUniqueId() directly
        Progress leaderProgress = forceGetProgressUUID(leaderUUID);
        Progress progress = forceGetProgressUUID(playerUUID);
        if (leaderProgress.equals(progress)) {
            logger.warning("Tried to migrate progress for player " + playerUUID + " but they are already the leader.");
            return;
        }
        progress.progress.forEach(leaderProgress::setProgress);
        progress.resetProgress(); // Clear the original player's progress after migration
        logger.info("Migrated progress for player " + playerUUID);
    }

    /**
     * Migrates the player's progress to their island leader's progress.
     * <p>
     * This method retrieves the player's island leader and migrates each progress entry
     * from the player's progress to the leader's progress. The leader's progress for each key
     * is incremented by the player's current progress for that key.
     * <p>
     * This is useful in scenarios where a player joins an island led by another player, and
     * they assume leadership of the island, migrating island progress to their progress file.
     *
     * @param player The player whose progress will be migrated to the leader's progress.
     */
    public static void migrateProgress(Player player) {
        migrateProgress(player.getUniqueId());
    }

    /**
     * Clears a player from both caches. This should be called when a player logs out
     * to prevent memory leaks.
     * @param player The player to remove from cache.
     */
    public static void clearCache(Player player) {
        UUID playerUUID = player.getUniqueId();
        Progress progress = uuidCache.get(playerUUID);
        if (progress != null) {
            progress.flushCache(); // Ensure data is saved before removing from cache
        }
        uuidCache.remove(playerUUID);
        playerCache.remove(player);
    }

    /**
     * Clears a player from cache by UUID. This should be called when a player logs out
     * to prevent memory leaks.
     * @param playerUUID The UUID of the player to remove from cache.
     */
    public static void clearCache(UUID playerUUID) {
        Progress progress = uuidCache.get(playerUUID);
        if (progress != null) {
            progress.flushCache(); // Ensure data is saved before removing from cache
        }
        uuidCache.remove(playerUUID);
        // Also remove from legacy cache if present
        playerCache.entrySet().removeIf(entry -> entry.getKey().getUniqueId().equals(playerUUID));
    }

    public final UUID playerUUID;
    @Deprecated // Use playerUUID instead
    public final Player player;
    private final Map<String, Double> progress = new TreeMap<>();
    private final File progressFile;
    private YamlConfiguration progressConfig;

    /**
     * Creates a new Progress object for the given player UUID.
     * It is generally not advised to create a new Progress object directly.
     * Use Progress.getProgress(UUID) instead to ensure proper caching and retrieval.
     */
    public Progress(UUID playerUUID) {
        this.playerUUID = playerUUID;
        this.player = plugin.getServer().getPlayer(playerUUID); // May be null if offline
        this.progressFile = new File(progressDir, playerUUID + ".yml");
        this.progressConfig = YamlConfiguration.loadConfiguration(progressFile);
        fetchCache();
    }

    /**
     * It is generally not advised to create a new Progress object directly.
     * Use Progress.getProgress(Player) instead to ensure proper caching and retrieval.
     * @deprecated Use Progress(UUID) constructor instead for better offline player support.
     */
    @Deprecated
    public Progress(Player player) {
        this(player.getUniqueId());
    }

    /**
     * Loads the player's progress from the YAML file into the progress cache.
     * <p>
     * <b>IMPORTANT: If inconsistency may happen,
     * this method and flushCache() should be called to ensure the cache is up-to-date.</b>
     */
    public void fetchCache() {
        progress.clear();
        if (progressFile.exists()) {
            progressConfig = YamlConfiguration.loadConfiguration(progressFile);
            for (String key : progressConfig.getKeys(false)) {
                if (key.startsWith("progress_")) {
                    String progressKey = key.substring(9); // Remove "progress_" prefix
                    double value = progressConfig.getDouble(key, 0.0);
                    progress.put(progressKey, value);
                }
            }
        }
    }

    /**
     * Saves the progress cache to the player's YAML file, then
     * fetch any possible external modifications.
     * <p>
     * <b>IMPORTANT: This should be called whenever inconsistency may happen.</b>
     */
    public void flushCache() {
        try {
            // Ensure the progress directory exists
            if (!progressDir.exists()) {
                if (!progressDir.mkdirs()) {
                    logger.severe("Failed to create progress directory: " + progressDir.getAbsolutePath());
                    return;
                }
            }

            // Clear existing progress entries
            for (String key : progressConfig.getKeys(false)) {
                if (key.startsWith("progress_")) {
                    progressConfig.set(key, null);
                }
            }

            // Save current progress
            for (Map.Entry<String, Double> entry : progress.entrySet()) {
                progressConfig.set("progress_" + entry.getKey(), entry.getValue());
            }

            progressConfig.save(progressFile);

            // Re-fetch to ensure consistency
            fetchCache();
        } catch (IOException e) {
            String playerName = player != null ? player.getName() : playerUUID.toString();
            logger.severe("Failed to save progress for player " + playerName + ": " + e.getMessage());
        }
    }

    /**
     * Sets the progress for the given key to the given value.
     * <p>
     * The key should be a unique identifier for the progress being tracked (e.g. "Challenge_1" or "Islands_Visited").
     * The value should be a double indicating the player's progress.
     * <p>
     * This will update the player's YAML file as well as the cache.
     * @param key The key for the progress to track.
     * @param value The value of the progress.
     */
    public void setProgress(String key, double value) {
        progress.put(key, value);
        try {
            progressConfig.set("progress_" + key, value);
            progressConfig.save(progressFile);
        } catch (IOException e) {
            String playerName = player != null ? player.getName() : playerUUID.toString();
            logger.severe("Failed to save progress for player " + playerName + ", key " + key + ": " + e.getMessage());
        }
    }

    /**
     * Returns the progress for the given key.
     * <p>
     * If the key does not exist, returns 0.
     * @param key The key for the progress to retrieve.
     * @return The progress for the given key.
     */
    public double getProgress(String key) {
        return progress.getOrDefault(key, 0.0);
    }

    /**
     * Adds the given value to the progress for the given key and returns the new value.
     * <p>
     * If the key does not exist, it will be created with the given value.
     * @param key The key for the progress to modify.
     * @param value The value to add to the progress.
     * @return The new value of the progress.
     */
    @SuppressWarnings("UnusedReturnValue")
    public double addToProgress(String key, double value) {
        double currentValue = getProgress(key);
        double newValue = currentValue + value;
        setProgress(key, newValue);
        return newValue;
    }

    /**
     * Resets all progress for the player.
     * <p>
     * This method clears the progress cache and removes all progress-related entries
     * from the player's YAML file. It effectively resets the player's
     * tracked progress to an initial state.
     */
    public void resetProgress() {
        progress.clear();
        try {
            // Clear all progress entries from the config
            for (String key : progressConfig.getKeys(false)) {
                if (key.startsWith("progress_")) {
                    progressConfig.set(key, null);
                }
            }
            progressConfig.save(progressFile);
        } catch (IOException e) {
            String playerName = player != null ? player.getName() : playerUUID.toString();
            logger.severe("Failed to reset progress for player " + playerName + ": " + e.getMessage());
        }
    }
}
