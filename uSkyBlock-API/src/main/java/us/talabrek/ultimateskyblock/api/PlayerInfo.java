package us.talabrek.ultimateskyblock.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Basic information about a Player in uSkyBlock.
 * @since 2.7.0
 */
public interface PlayerInfo {
    Player getPlayer();

    String getPlayerName();

    UUID getUniqueId();

    boolean getHasIsland();

    Location getIslandLocation();

    Location getIslandNetherLocation();

    /**
     * Gets the location of the player's island in the end world, or null if the player has no island or the end
     * is disabled.
     *
     * @return Location of the island in the end world, or null.
     * @since 3.2.0
     */
    Location getIslandEndLocation();

    Location getHomeLocation();

    String getDisplayName();

    Collection<ChallengeCompletion> getChallenges();

    List<String> getBannedFrom();

    List<String> getTrustedOn();

    IslandInfo getIslandInfo();
}
