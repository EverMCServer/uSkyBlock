package us.talabrek.ultimateskyblock.event;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import us.talabrek.ultimateskyblock.api.event.IslandLeaderChangedEvent;
import us.talabrek.ultimateskyblock.progress.Progress;

public class ProgressEvents implements Listener {

    /**
     * Handles the event when the leader of an island changes.
     * <p>
     * When the leader of an island changes, this method updates the progress for the new leader.
     * This ensures that the new leader has the correct progress data.
     *
     * @param event The island leader changed event.
     */
    @EventHandler
    public void onLeaderChangeEvent(IslandLeaderChangedEvent event) {
        Progress.migrateProgress(event.getOriginalLeaderInfo().getPlayer());
    }

    /**
     * Handles the event when a player uses a command related to visiting an island.
     * <p>
     * This method listens for the {@link PlayerCommandPreprocessEvent} and checks if the player
     * has executed a command starting with "/island warp" or "/is warp". If so, it updates
     * the player's progress by adding to the "visit" progress key.
     *
     * @param event The player command preprocess event.
     */
    @EventHandler
    public void onVisitEvent(PlayerCommandPreprocessEvent event) {
        if(event.getMessage().startsWith("/island warp") ||
            event.getMessage().startsWith("/is warp")) {
            Progress.getProgress(event.getPlayer()).addToProgress("visit", 1.0);
        }
    }

}
