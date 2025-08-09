package us.talabrek.ultimateskyblock.event;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import us.talabrek.ultimateskyblock.api.event.IslandLeaderChangedEvent;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.progress.Progress;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.UUID;

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
        // Use UUID-based API for better offline player support
        UUID originalLeaderUUID = event.getOriginalLeaderInfo().getUniqueId();
        Progress.migrateProgress(originalLeaderUUID);
    }

    /**
     * Handles player logout to clear progress cache and prevent memory leaks.
     * @param event The player quit event.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Progress.clearCache(event.getPlayer());
    }

    /**
     * Handles the event when a player uses a command related to visiting an island.
     * <p>
     * This method listens for the {@link PlayerCommandPreprocessEvent} and checks if the player
     * has executed a command starting with "/island warp" or "/is warp". If the command is valid
     * (target player exists, has an island, and has an active warp), it updates both the visitor's
     * "visit" progress and the visited player's "visited_by" progress.
     *
     * @param event The player command preprocess event.
     */
    @EventHandler
    public void onVisitEvent(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        if(message.startsWith("/island warp") || message.startsWith("/is warp")) {
            // Extract the target player name from the command
            String[] args = message.split(" ");
            if (args.length >= 3) { // "/island warp <player>" or "/is warp <player>"
                String targetPlayerName = args[2];

                // Get plugin instance
                uSkyBlock plugin = uSkyBlock.getInstance();
                Player visitor = event.getPlayer();

                // Check if visitor has permission to warp
                if (!visitor.hasPermission("usb.island.warp")) {
                    return;
                }

                // Check if visitor's island is generating
                PlayerInfo visitorInfo = plugin.getPlayerInfo(visitor);
                if (visitorInfo.isIslandGenerating()) {
                    return;
                }

                // Get target player info
                PlayerInfo targetPlayerInfo = plugin.getPlayerInfo(targetPlayerName);
                if (targetPlayerInfo == null || !targetPlayerInfo.getHasIsland()) {
                    return; // Target player doesn't exist or doesn't have an island
                }

                // Get target island info
                IslandInfo targetIsland = plugin.getIslandInfo(targetPlayerInfo);
                if (targetIsland == null || (!targetIsland.hasWarp() && !targetIsland.isTrusted(visitor))) {
                    return; // Target doesn't have an active warp and visitor is not trusted
                }

                // Check if target island is generating
                if (targetPlayerInfo.isIslandGenerating()) {
                    return;
                }

                // Check if visitor is banned from the island
                if (targetIsland.isBanned(visitor)) {
                    return;
                }

                // If we reach here, the warp command is valid
                // Add to visitor's visit progress using UUID-based API
                Progress.getProgress(visitor.getUniqueId()).addToProgress("visit", 1.0);

                // Add to target player's visited_by progress using UUID-based API
                // This works regardless of whether the target player is online or offline
                UUID targetLeaderUUID = targetIsland.getLeaderUniqueId();
                Progress.getProgress(targetLeaderUUID).addToProgress("visited_by", 1.0);
            }
        }
    }
}
