package us.talabrek.ultimateskyblock.event;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.api.event.IslandLeaderChangedEvent;
import us.talabrek.ultimateskyblock.progress.ProgressLogic;

import java.util.UUID;

/**
 * Lifecycle handlers for the progress system: migration on island-leader change and
 * cache cleanup on logout.
 */
@Singleton
public class ProgressEvents implements Listener {

    private final ProgressLogic progressLogic;

    @Inject
    public ProgressEvents(@NotNull ProgressLogic progressLogic) {
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
     * Handles player logout to flush and clear the progress cache and prevent memory leaks.
     *
     * @param event The player quit event.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        progressLogic.clearCache(event.getPlayer());
    }
}
