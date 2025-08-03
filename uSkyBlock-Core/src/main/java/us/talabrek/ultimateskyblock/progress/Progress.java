package us.talabrek.ultimateskyblock.progress;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * An abstraction of Spigot PersistentDataContainer to store player progress.
 * This provides a way to track game progress and support content creation.
 * <p><b>This mechanism is currently not thread-safe.</b>
 */
public class Progress {

    private static final Log log = LogFactory.getLog(Progress.class);
    private static uSkyBlock plugin;
    private static Logger logger;
    // Global cache for player progress
    private static final Map<Player, Progress> cache = new HashMap<>();

    /**
     * Initialize the Progress tracking mechanism.
     * This is called by the uSkyBlock plugin on startup.
     * @param plugin The uSkyBlock plugin instance.
     */
    public static void init(uSkyBlock plugin) {
        Progress.plugin = plugin;
        Progress.logger = plugin.getLogger();
    }

    /**
     * Retrieves the progress for a player. If the player's progress is not already
     * cached, it will be created and stored in the cache. Note that if the player is
     * not the leader of their party, the leader's progress will be returned instead.
     * @param player The player to retrieve progress for.
     * @return The cached progress for the player.
     */
    public static Progress getProgress(Player player) {
        String leaderName = plugin.getIslandInfo(player).getLeader();
        if(!player.getName().equals(leaderName)) {
            player = plugin.getServer().getPlayer(leaderName);
        }
        return forceGetProgressPlayer(player);
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
        if(cache.containsKey(player)) {
            return cache.get(player);
        } else {
            Progress progress = new Progress(player);
            cache.put(player, progress);
            return progress;
        }
    }

/**
 * Migrates the player's progress to their island leader's progress.
 * <p>
 * This method retrieves the player's island leader and migrates each progress entry
 * from the player's progress to the leader's progress. The leader's progress for each key
 * is incremented by the player's current progress for that key.
 * <p>
 * This is useful in scenarios where a player joins an island led by another player, and
 * they assume leadership of the island, migrating island progress to their PDC.
 *
 * @param player The player whose progress will be migrated to the leader's progress.
 */
    public static void migrateProgress(Player player) {
        IslandInfo island = plugin.getIslandInfo(player);
        Progress leaderProgress = forceGetProgressPlayer(plugin.getServer().getPlayer(island.getLeader()));
        Progress progress = forceGetProgressPlayer(player);
        if(leaderProgress.equals(progress)) {
            logger.warning("Tried to migrate progress for player " + player.getName() + " but they are already the leader.");
            return;
        }
        progress.progress.forEach(leaderProgress::setProgress);
        progress.resetProgress(); // Clear the original leader's progress after migration
        logger.info(player.getName() + " migrated progress for player " + player.getName());
    }

    public final Player player;
    private final Map<String, Double> progress = new TreeMap<>();
    private final PersistentDataContainer pdc;

    /**
     * It is generally not advised to create a new Progress object directly.
     * Use Progress.getProgress(Player) instead to ensure proper caching and retrieval.
     */
    public Progress(Player player) {
        this.player = player;
        this.pdc = player.getPersistentDataContainer();
        fetchCache();
    }

    /**
     * Loads the player's progress from the PersistentDataContainer into the progress cache.
     * <p>
     * <b>IMPORTANT: If inconsistency may happen,
     * this method and flushCache() should be called to ensure the cache is up-to-date.</b>
     */
    public void fetchCache() {
        for(NamespacedKey key: pdc.getKeys()) {
            if(key.getNamespace().equals("usb") && key.getKey().startsWith("progress_")) {
                String progressKey = key.getKey().substring(9); // Remove "progress_" prefix
                Double value = pdc.get(key, PersistentDataType.DOUBLE);
                if(value != null) {
                    progress.put(progressKey, value);
                }
            }
        }
    }

    /**
     * Saves the progress cache to the player's persistent data container, then
     * fetch any possible external modifications.
     * <p>
     * <b>IMPORTANT: This should be called whenever inconsistency may happen.</b>
     */
    public void flushCache() {
        for(Map.Entry<String, Double> entry: progress.entrySet()) {
            NamespacedKey key = NamespacedKey.fromString("usb:progress_" + entry.getKey());
            if(key != null) {
                pdc.set(key, PersistentDataType.DOUBLE, entry.getValue());
            }
        }
    }

    /**
     * Sets the progress for the given key to the given value.
     * <p>
     * The key should be a unique identifier for the progress being tracked (e.g. "Challenge_1" or "Islands_Visited").
     * The value should be a double indicating the player's progress.
     * <p>
     * This will update the player's persistent data container as well as the cache.
     * @param key The key for the progress to track.
     * @param value The value of the progress.
     */
    public void setProgress(String key, double value) {
        progress.put(key, value);
        NamespacedKey namespacedKey = NamespacedKey.fromString("usb:progress_" + key);
        if(namespacedKey != null) {
            pdc.set(namespacedKey, PersistentDataType.DOUBLE, value);
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
     * from the player's persistent data container. It effectively resets the player's
     * tracked progress to an initial state.
     */
    public void resetProgress() {
        progress.clear();
        for(NamespacedKey key: pdc.getKeys()) {
            if(key.getNamespace().equals("usb") && key.getKey().startsWith("progress_")) {
                pdc.remove(key);
            }
        }
    }

}
