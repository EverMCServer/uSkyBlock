package us.talabrek.ultimateskyblock.progress;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The progress data of a single island (keyed by the island leader's UUID).
 * <p>
 * Tracks two values per key:
 * <ul>
 *     <li><b>Current progress</b> ({@code progress_} prefix) — consumed by challenge completions,
 *     never reset by timers.</li>
 *     <li><b>Total progress</b> ({@code total_} prefix) — lifetime accumulation, never consumed or reset.</li>
 * </ul>
 * <p>Writes are batched: mutators only mark the entry dirty, {@link #flush()} persists to disk.
 * <p><b>This class is not thread-safe.</b> Only touch it from the main server thread.
 */
public class PlayerProgress {

    private final UUID playerUUID;
    private final File progressFile;
    private final Logger logger;

    private final Map<String, Double> progress = new TreeMap<>();
    private final Map<String, Double> totalProgress = new TreeMap<>();
    private boolean dirty = false;
    private final YamlConfiguration progressConfig;

    public PlayerProgress(@NotNull UUID playerUUID, @NotNull File progressFile, @NotNull Logger logger) {
        this.playerUUID = playerUUID;
        this.progressFile = progressFile;
        this.logger = logger;
        this.progressConfig = YamlConfiguration.loadConfiguration(progressFile);
        fetch();
    }

    /**
     * Loads the progress values from the YAML file into the caches.
     */
    private void fetch() {
        progress.clear();
        totalProgress.clear();
        if (progressFile.exists()) {
            for (String key : progressConfig.getKeys(false)) {
                if (key.startsWith("progress_")) {
                    progress.put(key.substring("progress_".length()), progressConfig.getDouble(key, 0.0));
                } else if (key.startsWith("total_")) {
                    totalProgress.put(key.substring("total_".length()), progressConfig.getDouble(key, 0.0));
                }
            }
        }
    }

    /**
     * Saves the cached progress to the YAML file if it is dirty.
     * <p>The write goes to a temp file first, then moves atomically, so a crash
     * cannot corrupt the existing valid file by truncating it.
     */
    public void flush() {
        if (!dirty) {
            return;
        }
        try {
            if (!progressFile.getParentFile().exists() && !progressFile.getParentFile().mkdirs()) {
                logger.severe("Failed to create progress directory: " + progressFile.getParentFile().getAbsolutePath());
                return;
            }
            // Clear existing progress entries, keeping any unrelated keys intact
            for (String key : progressConfig.getKeys(false)) {
                if (key.startsWith("progress_") || key.startsWith("total_")) {
                    progressConfig.set(key, null);
                }
            }
            for (Map.Entry<String, Double> entry : progress.entrySet()) {
                progressConfig.set("progress_" + entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Double> entry : totalProgress.entrySet()) {
                progressConfig.set("total_" + entry.getKey(), entry.getValue());
            }
            File tempFile = new File(progressFile.getParentFile(), "." + progressFile.getName() + ".tmp");
            progressConfig.save(tempFile);
            Files.move(tempFile.toPath(), progressFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save progress for player " + playerUUID + ": " + e.getMessage(), e);
        }
    }

    /**
     * Sets the current progress for the given key.
     * <p>The total progress is never touched by this method — challenge completions
     * consume via {@link #setProgress(String, double)}.
     *
     * @param key   The key for the progress to track.
     * @param value The value of the progress.
     */
    public void setProgress(String key, double value) {
        progress.put(key, value);
        dirty = true;
    }

    /**
     * Returns the current progress for the given key. Defaults to 0 if the key does not exist.
     *
     * @param key The key for the progress to retrieve.
     * @return The current progress for the given key.
     */
    public double getProgress(String key) {
        return progress.getOrDefault(key, 0.0);
    }

    /**
     * Returns the lifetime total progress for the given key. Defaults to 0 if the key does not exist.
     *
     * @param key The key for the progress to retrieve.
     * @return The total progress for the given key.
     */
    public double getTotalProgress(String key) {
        return totalProgress.getOrDefault(key, 0.0);
    }

    /**
     * Adds the given value to both the current and the total progress for the given key.
     *
     * @param key   The key for the progress to modify.
     * @param value The value to add to the progress.
     * @return The new current value of the progress.
     */
    public double addToProgress(String key, double value) {
        double newValue = getProgress(key) + value;
        double newTotal = getTotalProgress(key) + value;
        progress.put(key, newValue);
        totalProgress.put(key, newTotal);
        dirty = true;
        return newValue;
    }

    /**
     * Merges the current (overwrite) and total (additive) progress of another entry into this one.
     * Used when an island gets a new leader, migrating the island progress to the new leader's file.
     *
     * @param other The progress entry to merge from.
     */
    public void mergeFrom(@NotNull PlayerProgress other) {
        other.progress.forEach(this::setProgress);
        other.totalProgress.forEach((key, value) -> totalProgress.merge(key, value, Double::sum));
        dirty = true;
    }

    /**
     * Clears all progress (current and total) for this entry.
     */
    public void reset() {
        progress.clear();
        totalProgress.clear();
        dirty = true;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }
}
