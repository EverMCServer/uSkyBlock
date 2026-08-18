package us.talabrek.ultimateskyblock.challenge;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import dk.lockfuglsang.minecraft.file.FileUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;

/**
 * Responsible for handling ChallengeCompletions
 */
public class ChallengeCompletionLogic {

    private final uSkyBlock plugin;
    private final File storageFolder;
    private final boolean storeOnIsland;
    private final ChallengeRankingLogic rankingLogic;
    private final LoadingCache<String, Map<String, ChallengeCompletion>> completionCache;

    public ChallengeCompletionLogic(uSkyBlock plugin, FileConfiguration config, ChallengeRankingLogic rankingLogic) {
        this.plugin = plugin;
        this.rankingLogic = rankingLogic;
        storeOnIsland = config.getString("challengeSharing", "island").equalsIgnoreCase("island");
        completionCache = CacheBuilder
            .from(plugin.getConfig().getString("options.advanced.completionCache", "maximumSize=200,expireAfterWrite=15m,expireAfterAccess=10m"))
            .removalListener((RemovalListener<String, Map<String, ChallengeCompletion>>) removal -> saveToFile(removal.getKey(), removal.getValue()))
            .build(new CacheLoader<>() {
                       @Override
                       public @NotNull Map<String, ChallengeCompletion> load(@NotNull String id) {
                           return loadFromFile(id);
                       }
                   }
            );
        storageFolder = new File(plugin.getDataFolder(), "completion");
        if (!storageFolder.exists() || !storageFolder.isDirectory()) {
            storageFolder.mkdirs();
        }
    }

    private void saveToFile(String id, Map<String, ChallengeCompletion> map) {
        File configFile = new File(storageFolder, id + ".yml");
        if (map == null || map.isEmpty()) {
            // Don't write empty files — they would be loaded as empty maps and perpetuate data loss.
            // Delete any stale empty file to break the cycle.
            if (configFile.exists()) {
                configFile.delete();
            }
            return;
        }
        FileConfiguration fileConfiguration = new YamlConfiguration();
        saveToConfiguration(fileConfiguration, map);
        // Write to a temp file first, then rename atomically. This prevents a partial
        // write (e.g. due to disk full, crash, or concurrent read) from corrupting
        // the existing valid file by truncating it.
        File tempFile = new File(storageFolder, "." + id + ".yml.tmp");
        try {
            fileConfiguration.save(tempFile);
            // Atomic move: replaces the target file only after the temp file is fully written
            Files.move(tempFile.toPath(), configFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Unable to store challenge-completion to " + configFile, e);
            // Clean up orphaned temp file
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private void saveToConfiguration(FileConfiguration configuration, Map<String, ChallengeCompletion> map) {
        for (Map.Entry<String, ChallengeCompletion> entry : map.entrySet()) {
            String challengeName = entry.getKey();
            ChallengeCompletion completion = entry.getValue();
            ConfigurationSection section = configuration.createSection(challengeName);
            Instant cooldownUntil = completion.cooldownUntil();
            Long cooldown = cooldownUntil != null ? cooldownUntil.toEpochMilli() : null;
            section.set("firstCompleted", cooldown);
            section.set("timesCompleted", completion.getTimesCompleted());
            section.set("timesCompletedSinceTimer", completion.getTimesCompletedInCooldown());
        }
    }

    private Map<String, ChallengeCompletion> loadFromFile(String id) {
        File configFile = new File(storageFolder, id + ".yml");
        if (!configFile.exists() && storeOnIsland) {
            IslandInfo islandInfo = plugin.getIslandInfo(id);
            if (islandInfo != null && islandInfo.getLeader() != null && islandInfo.getLeaderUniqueId() != null) {
                File leaderFile = new File(storageFolder, islandInfo.getLeaderUniqueId().toString() + ".yml");
                if (leaderFile.exists()) {
                    leaderFile.renameTo(configFile);
                }
            }
        }
        // Try to load the main file
        Map<String, ChallengeCompletion> result = tryLoadFile(configFile);
        if (result != null) {
            return result;
        }
        // Main file is missing or corrupt — try to recover from a temp file
        // (which may have been fully written but not renamed due to a crash)
        File tempFile = new File(storageFolder, "." + id + ".yml.tmp");
        result = tryLoadFile(tempFile);
        if (result != null) {
            plugin.getLogger().log(Level.WARNING,
                "Recovered challenge completion data from temp file: {0}", tempFile.getAbsolutePath());
            // Rename the recovered temp file to the proper location
            try {
                Files.move(tempFile.toPath(), configFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to rename recovered temp file: " + tempFile, e);
            }
            return result;
        }
        // No recoverable data found — return an empty map.
        // Note: this empty map will not be persisted since saveToFile skips empty maps.
        return new ConcurrentHashMap<>();
    }

    /**
     * Attempts to load a challenge completion map from the given file.
     * Returns null if the file does not exist, is empty, or cannot be parsed.
     */
    private Map<String, ChallengeCompletion> tryLoadFile(File configFile) {
        if (!configFile.exists()) {
            return null;
        }
        if (configFile.length() == 0) {
            // Zero-byte file — treat as corrupt/missing
            return null;
        }
        FileConfiguration fileConfiguration = new YamlConfiguration();
        FileUtil.readConfig(fileConfiguration, configFile);
        if (fileConfiguration.getRoot() != null) {
            Map<String, ChallengeCompletion> map = loadFromConfiguration(fileConfiguration.getRoot());
            if (!map.isEmpty()) {
                return map;
            }
        }
        // File exists but produced no valid data — log a warning
        plugin.getLogger().log(Level.WARNING,
            "Completion file {0} exists (size={1}) but has no valid data. " +
            "Check for a .err backup file created by FileUtil.",
            new Object[]{configFile.getAbsolutePath(), configFile.length()});
        return null;
    }

    private Map<String, ChallengeCompletion> loadFromConfiguration(ConfigurationSection root) {
        Map<String, ChallengeCompletion> challengeMap = new ConcurrentHashMap<>();
        plugin.getChallengeLogic().populateChallenges(challengeMap);
        if (root != null) {
            for (String challengeName : challengeMap.keySet()) {
                long firstCompleted = root.getLong(challengeName + ".firstCompleted", 0);
                Instant firstCompletedDuration = firstCompleted > 0 ? Instant.ofEpochMilli(firstCompleted) : null;
                challengeMap.put(challengeName, new ChallengeCompletion(
                    challengeName,
                    firstCompletedDuration,
                    root.getInt(challengeName + ".timesCompleted", 0),
                    root.getInt(challengeName + ".timesCompletedSinceTimer", 0)
                ));
            }
        }
        return challengeMap;
    }

    public Map<String, ChallengeCompletion> getIslandChallenges(String islandName) {
        if (storeOnIsland && islandName != null) {
            try {
                return completionCache.get(islandName);
            } catch (ExecutionException e) {
                plugin.getLogger().log(Level.WARNING, "Error fetching challenge-completion for id " + islandName);
            }
        }
        return new ConcurrentHashMap<>();
    }

    public Map<String, ChallengeCompletion> getChallenges(PlayerInfo playerInfo) {
        if (playerInfo == null || !playerInfo.getHasIsland() || playerInfo.locationForParty() == null) {
            return new ConcurrentHashMap<>();
        }
        String id = getCacheId(playerInfo);
        Map<String, ChallengeCompletion> challengeMap = new ConcurrentHashMap<>();
        try {
            challengeMap = completionCache.get(id);
        } catch (ExecutionException e) {
            plugin.getLogger().log(Level.WARNING, "Error fetching challenge-completion for id " + id);
        }
        if (challengeMap.isEmpty()) {
            // Fetch from the player-yml file
            challengeMap = loadFromConfiguration(playerInfo.getConfig().getConfigurationSection("player.challenges"));
            if (!challengeMap.isEmpty()) {
                completionCache.put(id, challengeMap);
            }
            // Wipe it
            playerInfo.getConfig().set("player.challenges", null);
            playerInfo.save();
        }
        return challengeMap;
    }

    private String getCacheId(PlayerInfo playerInfo) {
        return storeOnIsland ? playerInfo.locationForParty() : playerInfo.getUniqueId().toString();
    }

    public void completeChallenge(PlayerInfo playerInfo, String challengeName) {
        Map<String, ChallengeCompletion> challenges = getChallenges(playerInfo);
        if (challenges.containsKey(challengeName)) {
            ChallengeCompletion completion = challenges.get(challengeName);
            if (!completion.isOnCooldown()) {
                Duration resetDuration = plugin.getChallengeLogic().getResetDuration(challengeName);
                if (resetDuration.isPositive()) {
                    Instant now = Instant.now();
                    completion.setCooldownUntil(now.plus(resetDuration));
                } else {
                    completion.setCooldownUntil(null);
                }
            }
            completion.addTimesCompleted();
            updateRanking(playerInfo, challenges);
        }
    }

    public void resetChallenge(PlayerInfo playerInfo, String challenge) {
        Map<String, ChallengeCompletion> challenges = getChallenges(playerInfo);
        if (challenges.containsKey(challenge)) {
            challenges.get(challenge).setTimesCompleted(0);
            challenges.get(challenge).setCooldownUntil(null);
            updateRanking(playerInfo, challenges);
        }
    }

    public int checkChallenge(PlayerInfo playerInfo, String challengeName) {
        Map<String, ChallengeCompletion> challenges = getChallenges(playerInfo);
        if (challenges.containsKey(challengeName)) {
            return challenges.get(challengeName).getTimesCompleted();
        }
        return 0;
    }

    public ChallengeCompletion getChallenge(PlayerInfo playerInfo, String challenge) {
        Map<String, ChallengeCompletion> challenges = getChallenges(playerInfo);
        return challenges.get(challenge);
    }

    public void resetAllChallenges(PlayerInfo playerInfo) {
        Map<String, ChallengeCompletion> challengeMap = new ConcurrentHashMap<>();
        plugin.getChallengeLogic().populateChallenges(challengeMap);
        completionCache.put(getCacheId(playerInfo), challengeMap);
        updateRanking(playerInfo, challengeMap);
    }

    private void updateRanking(PlayerInfo playerInfo, Map<String, ChallengeCompletion> challenges) {
        if (storeOnIsland && playerInfo.getHasIsland()) {
            rankingLogic.recordCompletion(playerInfo.locationForParty(), challenges);
        }
    }

    public void shutdown() {
        flushCache();
    }

    public long flushCache() {
        long size = completionCache.size();
        completionCache.invalidateAll();
        return size;
    }

    public boolean isIslandSharing() {
        return storeOnIsland;
    }

}
