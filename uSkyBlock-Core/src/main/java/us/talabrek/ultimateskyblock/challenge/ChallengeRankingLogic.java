package us.talabrek.ultimateskyblock.challenge;

import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import dk.lockfuglsang.minecraft.file.FileUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;
import us.talabrek.ultimateskyblock.util.IslandUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

/**
 * In-memory leaderboard of completed (distinct) challenges per island.
 * <p>
 * The leaderboard is not persisted - it is rebuilt from the completion files on startup
 * (see {@link #rebuild()}) and updated incrementally as challenges are completed,
 * reset, or islands are deleted.
 */
@Singleton
public class ChallengeRankingLogic {

    private static final int PAGE_SIZE = 10;

    private final uSkyBlock plugin;
    private final List<ChallengeRank> ranks = new ArrayList<>();
    private volatile Set<String> validChallengeNames;

    @Inject
    public ChallengeRankingLogic(@NotNull uSkyBlock plugin) {
        this.plugin = plugin;
    }

    /**
     * Rebuilds the in-memory leaderboard from the island completion files on disk.
     * Must be called from an async thread (it does file I/O).
     */
    public void rebuild() {
        if (!plugin.getChallengeLogic().isIslandSharing()) {
            synchronized (ranks) {
                ranks.clear();
            }
            return;
        }
        Set<String> valid = new HashSet<>(plugin.getChallengeLogic().getAllChallengeNames());
        List<ChallengeRank> rebuilt = new ArrayList<>();
        File islandsDir = plugin.getIslandLogic().getIslandDirectory().toFile();
        String[] files = islandsDir.list(IslandUtil.createIslandFilenameFilter());
        if (files == null) {
            return;
        }
        File completionDir = new File(plugin.getDataFolder(), "completion");
        for (String file : files) {
            String islandName = FileUtil.getBasename(file);
            int count = countDistinctFromFile(new File(completionDir, islandName + ".yml"), valid);
            if (count > 0) {
                rebuilt.add(new ChallengeRank(islandName, count));
            }
        }
        Collections.sort(rebuilt);
        synchronized (ranks) {
            ranks.clear();
            ranks.addAll(rebuilt);
        }
    }

    /**
     * Recomputes the distinct-completion count of an island from its completion map
     * and updates the in-memory leaderboard. Called on every completion/reset of a challenge.
     */
    public void recordCompletion(String islandName, Map<String, ChallengeCompletion> challenges) {
        if (islandName == null) {
            return;
        }
        int count = countDistinct(challenges);
        synchronized (ranks) {
            ChallengeRank existing = find(islandName);
            if (count == 0) {
                if (existing != null) {
                    ranks.remove(existing);
                }
                return;
            }
            if (existing != null) {
                if (existing.count == count) {
                    return; // No change (e.g. a repeatable challenge was completed again)
                }
                existing.count = count;
            } else {
                ranks.add(new ChallengeRank(islandName, count));
            }
            Collections.sort(ranks);
        }
    }

    /**
     * Removes an island from the in-memory leaderboard (called on island deletion).
     */
    public void removeIsland(String islandName) {
        if (islandName == null) {
            return;
        }
        synchronized (ranks) {
            ChallengeRank existing = find(islandName);
            if (existing != null) {
                ranks.remove(existing);
            }
        }
    }

    /**
     * Displays the leaderboard to the sender, mirroring {@code IslandLogic.displayTopTen}.
     */
    public void showTop(CommandSender sender, int page) {
        // Snapshot the page under the lock; leader-name resolution may load IslandInfo from disk
        // and is done outside the lock.
        List<ChallengeRank> pageRanks;
        int maxpage;
        int place;
        boolean hasMyIsland = false;
        int myCount = 0;
        int myRankIndex = -1;
        synchronized (ranks) {
            maxpage = Math.max(1, ((ranks.size() - 1) / PAGE_SIZE) + 1);
            if (page > maxpage) {
                page = maxpage;
            }
            if (page < 1) {
                page = 1;
            }
            place = (page - 1) * PAGE_SIZE + 1;
            pageRanks = new ArrayList<>(ranks.subList((page - 1) * PAGE_SIZE, Math.min(ranks.size(), PAGE_SIZE * page)));
            PlayerInfo playerInfo = plugin.getPlayerInfo(sender.getName());
            if (playerInfo != null && playerInfo.getHasIsland()) {
                hasMyIsland = true;
                ChallengeRank myRank = find(playerInfo.locationForParty());
                if (myRank != null) {
                    myCount = myRank.count;
                    myRankIndex = ranks.indexOf(myRank);
                }
            }
        }
        sender.sendMessage(tr("§eCHALLENGE TOP (page {0} of {1}):", page, maxpage));
        if (pageRanks.isEmpty()) {
            sender.sendMessage(tr("§4Challenge top list is empty! No island has completed any challenges yet."));
        }
        for (ChallengeRank rank : pageRanks) {
            String leader = getLeaderName(rank.islandName);
            String message = String.format(tr("§a#%2d §7(%d): §e%s §7(%s)"),
                place, rank.count, leader, rank.islandName);
            if (sender instanceof Player target) {
                String warpString = getJsonWarpString(
                    message,
                    tr("Click to warp to the island!"),
                    String.format("/is w %s", leader)
                );
                plugin.execCommand(target, "console:tellraw " +
                    target.getName() + " " + warpString, false);
            } else {
                sender.sendMessage(message);
            }
            place++;
        }
        if (hasMyIsland) {
            sender.sendMessage(tr("§eYour challenge count is: §f{0}", myCount));
            if (myRankIndex >= 0) {
                sender.sendMessage(tr("§eYour rank is: §f{0}", myRankIndex + 1));
            }
        }
    }

    /**
     * Flushes the completion cache to disk, then rebuilds and displays the leaderboard.
     * Used by admins (usb.admin.ctop) to force a fresh read of the on-disk data.
     */
    public void forceRebuild(CommandSender sender, int page) {
        plugin.getScheduler().async(() -> {
            plugin.getChallengeLogic().flushCache();
            rebuild();
            showTop(sender, page);
        });
    }

    private int countDistinct(Map<String, ChallengeCompletion> challenges) {
        Set<String> valid = getValidChallengeNames();
        int count = 0;
        for (ChallengeCompletion completion : challenges.values()) {
            if (completion.getTimesCompleted() > 0 && valid.contains(completion.getName())) {
                count++;
            }
        }
        return count;
    }

    private Set<String> getValidChallengeNames() {
        Set<String> valid = validChallengeNames;
        if (valid == null) {
            valid = new HashSet<>(plugin.getChallengeLogic().getAllChallengeNames());
            validChallengeNames = valid;
        }
        return valid;
    }

    private int countDistinctFromFile(File configFile, Set<String> valid) {
        if (!configFile.exists() || configFile.length() == 0) {
            return 0;
        }
        YamlConfiguration configuration = new YamlConfiguration();
        FileUtil.readConfig(configuration, configFile);
        if (configuration.getRoot() == null) {
            return 0;
        }
        int count = 0;
        for (String challengeName : configuration.getRoot().getKeys(false)) {
            if (valid.contains(challengeName) && configuration.getInt(challengeName + ".timesCompleted", 0) > 0) {
                count++;
            }
        }
        return count;
    }

    private ChallengeRank find(String islandName) {
        for (ChallengeRank rank : ranks) {
            if (rank.islandName.equalsIgnoreCase(islandName)) {
                return rank;
            }
        }
        return null;
    }

    private String getLeaderName(String islandName) {
        IslandInfo islandInfo = plugin.getIslandInfo(islandName);
        if (islandInfo != null && islandInfo.getLeader() != null) {
            return islandInfo.getLeader();
        }
        return islandName;
    }

    private String getJsonWarpString(String text, String hoverText, String command) {
        Map<String, Object> hoverEvent = new HashMap<>();
        hoverEvent.put("action", "show_text");
        hoverEvent.put("value", hoverText);

        Map<String, Object> clickEvent = new HashMap<>();
        clickEvent.put("action", "run_command");
        clickEvent.put("value", command);

        Map<String, Object> rootMap = new HashMap<>();
        rootMap.put("text", text);
        rootMap.put("hoverEvent", hoverEvent);
        rootMap.put("clickEvent", clickEvent);

        return new GsonBuilder().create().toJson(rootMap);
    }

    /**
     * A single leaderboard entry: island name and its distinct challenge-completion count.
     */
    public static class ChallengeRank implements Comparable<ChallengeRank> {
        private final String islandName;
        private int count;

        ChallengeRank(String islandName, int count) {
            this.islandName = islandName;
            this.count = count;
        }

        public String getIslandName() {
            return islandName;
        }

        public int getCount() {
            return count;
        }

        @Override
        public int compareTo(ChallengeRank o) {
            int cmp = Integer.compare(o.count, count);
            return cmp != 0 ? cmp : islandName.compareTo(o.islandName);
        }
    }
}
