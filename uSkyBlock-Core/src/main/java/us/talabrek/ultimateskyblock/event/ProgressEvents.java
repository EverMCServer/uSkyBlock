package us.talabrek.ultimateskyblock.event;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.api.event.IslandLeaderChangedEvent;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.progress.ProgressLogic;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lifecycle handlers for the progress system: migration on island-leader change,
 * cache cleanup on logout and the island-visit content trigger.
 */
@Singleton
public class ProgressEvents implements Listener {

    /** 访问计数累积 CD: 只计次数不去重, 防止原地移动/反复进出刷进度 */
    private static final long VISIT_PROGRESS_COOLDOWN_MS = 5 * 60 * 1000L;

    private final uSkyBlock plugin;
    private final ProgressLogic progressLogic;
    private final Map<UUID, Long> lastVisitAccumulation = new HashMap<>();
    /** 玩家当前所在的他人岛屿 (仅进入时计数, 岛内移动/挂机不重复计) */
    private final Map<UUID, String> lastForeignIsland = new HashMap<>();

    @Inject
    public ProgressEvents(@NotNull uSkyBlock plugin, @NotNull ProgressLogic progressLogic) {
        this.plugin = plugin;
        this.progressLogic = progressLogic;
    }

    /**
     * Handles the event when the leader of an island changes.
     * <p>
     * When the leader of an island changes, this method migrates the original leader's
     * progress to the new leader, so the island keeps its accumulated progress.
     *
     * @param event The island leader changed event.
     */
    @EventHandler
    public void onLeaderChangeEvent(IslandLeaderChangedEvent event) {
        UUID originalLeaderUUID = event.getOriginalLeaderInfo().getUniqueId();
        progressLogic.migrateProgress(originalLeaderUUID);
    }

    /**
     * Island-visit progress trigger: when a player moves onto another island, adds +1
     * to the visitor's {@code visit} key and +1 to the visited island's
     * {@code visited_by} key (island-leader entry, so it also works while the leader
     * is offline).
     * <p>Only counting island <em>entries</em> — moving around or AFK-ing inside the
     * same island does not accumulate (no dedup between visits: leaving and coming
     * back counts again). Accumulation per player is rate-limited to one per 5
     * minutes, so bouncing across a border cannot spam progress.
     *
     * @param event The player move event.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        IslandInfo island = plugin.getIslandInfo(event.getTo());
        if (island == null || island.getMemberUUIDs().contains(playerId)) {
            lastForeignIsland.remove(playerId); // 不在他人岛屿 (自己的岛/野外)
            return;
        }
        if (island.getName().equals(lastForeignIsland.get(playerId))) {
            return; // 已在同一座他人岛屿内, 只有进入才算到访
        }
        lastForeignIsland.put(playerId, island.getName());
        long now = System.currentTimeMillis();
        Long last = lastVisitAccumulation.get(playerId);
        if (last != null && now - last < VISIT_PROGRESS_COOLDOWN_MS) {
            return; // CD 内
        }
        lastVisitAccumulation.put(playerId, now);
        progressLogic.addToProgress(player, "visit", 1);
        UUID leaderUUID = island.getLeaderUniqueId();
        if (leaderUUID != null) {
            // visited_by 归属被访问岛: 加到岛主条目, 岛主离线也可累积
            progressLogic.getProgress(leaderUUID).addToProgress("visited_by", 1);
        }
    }

    /**
     * Handles player logout to flush and clear the progress cache and prevent memory leaks.
     *
     * @param event The player quit event.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        progressLogic.clearCache(event.getPlayer());
        UUID playerId = event.getPlayer().getUniqueId();
        lastVisitAccumulation.remove(playerId);
        lastForeignIsland.remove(playerId);
    }
}
