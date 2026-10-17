package us.talabrek.ultimateskyblock.event;

import com.google.common.util.concurrent.RateLimiter;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import us.talabrek.ultimateskyblock.Settings;
import us.talabrek.ultimateskyblock.handler.WorldGuardHandler;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;

import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

/**
 * Handles travelling between the overworld island and its part of the end world.
 *
 * <p>The end world is, like the nether, split into per island regions (same X/Z as the overworld island). Unlike the
 * nether, the end portal has no coordinate scaling: the vanilla destination is always the end world spawn. Every
 * destination therefore has to be rewritten explicitly to the island the portal belongs to.</p>
 *
 * <p>Every entity is allowed through the portal (technical Minecraft), so the destination is rewritten for entities
 * as well. This makes redstone contraptions that rely on entities travelling through an end portal (such as sand
 * duplication) work at the island's own arrival point.</p>
 */
@Singleton
public class EndPortalEvents implements Listener {

    /**
     * Radius (in blocks) of the arrival platform. Vanilla generates a 5x5 platform, so a radius of 2 matches it.
     */
    private static final int PLATFORM_RADIUS = 2;

    /**
     * Number of air blocks above the arrival platform. Vanilla clears the three blocks above the platform every time
     * an entity arrives, so anything built there would be destroyed (and dropped) on each entry.
     */
    private static final int PLATFORM_CLEARANCE = 3;

    private final uSkyBlock plugin;
    private final RateLimiter rateLimiter = RateLimiter.create(1);

    @Inject
    public EndPortalEvents(@NotNull uSkyBlock plugin) {
        this.plugin = plugin;
    }

    /**
     * Blocks ender pearl travel out of the player's own end region.
     *
     * <p>Ender pearls (and chorus fruit) are the only way to cross an island border in the end, because the world is
     * pure void: a pearl thrown past the border drops the player into the void, and a pearl aimed at another island
     * would let players skip over the per island protection. Cancelling such a teleport also protects the thrower,
     * who would otherwise die for a single mis-thrown pearl.</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPearlTeleport(PlayerTeleportEvent event) {
        if (!Settings.end_enabled || event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            return;
        }
        Location to = event.getTo();
        if (to == null || !plugin.getWorldManager().isSkyEnd(to.getWorld())) {
            return;
        }
        if (isInsideAnyIslandRegion(to)) {
            return;
        }
        event.setCancelled(true);
        if (rateLimiter.tryAcquire()) {
            event.getPlayer().sendMessage(tr("\u00a7cYou cannot leave your island in the end!"));
        }
    }

    /**
     * Checks whether the given location is inside one of the island regions of the end world. Used to decide whether
     * a location is safe to travel to.
     *
     * @param location Location in the end world.
     * @return True when the location is part of an island region.
     */
    private boolean isInsideAnyIslandRegion(@NotNull Location location) {
        if (location.getWorld() == null) {
            return false;
        }
        int radius = Settings.island_radius;
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                int gridX = Math.floorDiv(location.getBlockX(), Settings.island_distance) * Settings.island_distance;
                int gridZ = Math.floorDiv(location.getBlockZ(), Settings.island_distance) * Settings.island_distance;
                gridX += x * Settings.island_distance;
                gridZ += z * Settings.island_distance;
                Location candidate = new Location(location.getWorld(), gridX, Settings.end_height, gridZ);
                ProtectedRegion region = WorldGuardHandler.getEndRegionAt(candidate);
                if (region != null && region.contains(location.getBlockX(), location.getBlockY(), location.getBlockZ())) {
                    return true;
                }
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (!Settings.end_enabled || event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            return;
        }
        @Nullable World endWorld = plugin.getWorldManager().getEndWorld();
        if (endWorld == null) {
            return;
        }

        Location from = event.getFrom();
        Player player = event.getPlayer();
        if (plugin.getWorldManager().isSkyEnd(from.getWorld())) {
            // Returning from the end to the overworld island the region belongs to.
            Location target = getOverworldDestination(from, player);
            if (target != null) {
                event.setTo(target);
            }
            return;
        }
        if (!plugin.getWorldManager().isSkyWorld(from.getWorld())) {
            return; // Not our portal (e.g. a portal in an unrelated world).
        }

        @Nullable String islandName = WorldGuardHandler.getIslandNameAt(from);
        if (islandName == null) {
            // E.g. the world spawn or the island lobby - there is no island to link to.
            event.setCancelled(true);
            player.sendMessage(tr("\u00a7cThis end portal is not linked to an island."));
            return;
        }
        IslandInfo islandInfo = plugin.getIslandInfo(islandName);
        PlayerInfo playerInfo = plugin.getPlayerInfo(player);
        if (islandInfo == null || playerInfo == null || !playerInfo.getHasIsland()) {
            event.setCancelled(true);
            player.sendMessage(tr("\u00a7cYou need your own island before you can enter the end!"));
            return;
        }

        Location to = new Location(endWorld, islandInfo.getIslandLocation().getBlockX(),
            Settings.end_height, islandInfo.getIslandLocation().getBlockZ());
        event.setTo(to);
        ensureArrivalPlatformLater(to);
        // TODO: award the "enter_end" challenge here (destination validated, player about to enter) once the
        //       challenge semantics are finalised - intentionally disabled for now.
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        World fromWorld = event.getFrom().getWorld();
        if (!Settings.end_enabled || event.getPortalType() != org.bukkit.PortalType.ENDER) {
            return;
        }
        if (event.getTo() == null || fromWorld == null || !plugin.getWorldManager().isSkyWorld(fromWorld)) {
            return; // We only rewrite portals leaving the overworld island.
        }

        @Nullable World endWorld = plugin.getWorldManager().getEndWorld();
        if (endWorld == null) {
            return;
        }

        @Nullable String islandName = WorldGuardHandler.getIslandNameAt(event.getFrom());
        Location to = islandName != null
            ? getEndLocation(endWorld, plugin.getIslandInfo(islandName))
            : getEndFallbackLocation(endWorld);
        if (to == null) {
            return; // No island could be resolved - better to let vanilla handle it than to drop it in the void.
        }

        event.setTo(to);
        ensureArrivalPlatformLater(to);
    }

    /**
     * The end world is shared by all islands and has no dragon, so the respawn ritual (end crystals around the exit
     * portal) makes no sense there and would summon a dragon for everybody. Placing crystals in the end is blocked.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEndCrystalPlace(EntityPlaceEvent event) {
        if (!Settings.end_enabled || !plugin.getWorldManager().isSkyEnd(event.getEntity().getWorld())) {
            return;
        }
        if (event.getEntityType() != EntityType.END_CRYSTAL) {
            return;
        }
        event.setCancelled(true);
    }

    /**
     * End crystals (and dragons) can destroy terrain. Since the end world holds player builds, explosions caused by
     * end crystals and dragons are cancelled.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEndExplode(EntityExplodeEvent event) {
        if (!Settings.end_enabled || !plugin.getWorldManager().isSkyEnd(event.getEntity().getWorld())) {
            return;
        }
        if (event.getEntity() instanceof EnderCrystal || event.getEntity() instanceof EnderDragon) {
            event.setCancelled(true);
        }
    }

    /**
     * Builds (or repairs) the arrival platform at the given location. The vanilla generator recreates a 5x5 obsidian
     * platform whenever an entity enters the end, but only at the destination it knows about. Building it ourselves
     * makes sure the arrival point is always safe, and keeps the platform at exactly the same place so players can
     * rely on it for their contraptions.
     *
     * @param location Center of the arrival platform.
     */    public static void buildArrivalPlatform(@NotNull Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        int centerX = location.getBlockX();
        int centerY = location.getBlockY();
        int centerZ = location.getBlockZ();
        for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
            for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                world.getBlockAt(centerX + x, centerY - 1, centerZ + z).setType(Material.OBSIDIAN);
                for (int y = 0; y < PLATFORM_CLEARANCE; y++) {
                    Block above = world.getBlockAt(centerX + x, centerY + y, centerZ + z);
                    if (above.getType() != Material.AIR) {
                        above.setType(Material.AIR);
                    }
                }
            }
        }
    }

    /**
     * Gets the end arrival location of the given island.
     *
     * @param endWorld End world.
     * @param island   Island to get the arrival location for, may be null.
     * @return Arrival location, or null when the island is unknown.
     */
    @Nullable
    public static Location getEndLocation(@NotNull World endWorld, @Nullable IslandInfo island) {
        if (island == null || island.getIslandLocation() == null) {
            return null;
        }
        Location islandLocation = island.getIslandLocation();
        return new Location(endWorld, islandLocation.getBlockX(), Settings.end_height, islandLocation.getBlockZ());
    }

    /**
     * Gets the fallback location in the end world, used when no island can be resolved for a portal. It is the
     * world spawn / shared end "main city" around the origin (config {@code end.fallback.x/z}); the plugin places
     * no blocks there, so until the city has a floor falling entities are lost.
     *
     * @param endWorld End world.
     * @return Fallback location.
     */
    @NotNull
    public static Location getEndFallbackLocation(@NotNull World endWorld) {
        return new Location(endWorld, Settings.end_fallbackX, Settings.end_height, Settings.end_fallbackZ);
    }

    @Nullable
    private Location getOverworldDestination(Location from, Player player) {
        IslandInfo islandInfo = plugin.getIslandInfo(WorldGuardHandler.getIslandNameAt(from));
        if (islandInfo != null) {
            @Nullable Location home = islandInfo.getWarpLocation();
            if (home != null) {
                return home;
            }
            PlayerInfo leader = plugin.getPlayerInfo(islandInfo.getLeader());
            if (leader != null && leader.getHomeLocation() != null) {
                return leader.getHomeLocation();
            }
            return islandInfo.getIslandLocation();
        }

        PlayerInfo playerInfo = plugin.getPlayerInfo(player);
        if (playerInfo != null && playerInfo.getHasIsland() && playerInfo.getHomeLocation() != null) {
            return playerInfo.getHomeLocation();
        }
        return plugin.getWorldManager().getWorld().getSpawnLocation();
    }

    /**
     * Makes sure the arrival platform exists right after the teleport has been processed. Building it immediately
     * would force a synchronous chunk load; the teleport itself loads the destination chunk anyway.
     *
     * @param destination Destination of the teleport.
     */
    private void ensureArrivalPlatformLater(@NotNull Location destination) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            World world = destination.getWorld();
            if (world == null || !world.isChunkLoaded(destination.getBlockX() >> 4, destination.getBlockZ() >> 4)) {
                return;
            }
            if (!hasNearbyObserver(destination)) {
                // Nobody is around to see it - the vanilla platform (or the platform built when the island was
                // created) covers this case, and we avoid keeping chunks alive for nothing.
                return;
            }
            buildArrivalPlatform(destination);
        });
    }

    private boolean hasNearbyObserver(@NotNull Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        Vector center = location.toVector();
        for (Player player : world.getPlayers()) {
            if (player.getLocation().toVector().distanceSquared(center) < 128 * 128) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether the given location is inside the given island region of the end world.
     *
     * @param location Location to check.
     * @param island   Island owning the region.
     * @return True when the location is inside the island's end region.
     */
    public static boolean isInEndRegion(@NotNull Location location, @NotNull IslandInfo island) {
        Location islandLocation = island.getIslandLocation();
        if (islandLocation == null || location.getWorld() == null) {
            return false;
        }
        Location endIsland = new Location(location.getWorld(), islandLocation.getBlockX(),
            Settings.end_height, islandLocation.getBlockZ());
        ProtectedRegion region = WorldGuardHandler.getEndRegionAt(endIsland);
        return region != null && region.contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /**
     * Utility used by command handlers to check whether an entity is currently in the end world.
     *
     * @param entity Entity to check.
     * @return True when the entity is in the end world.
     */
    public static boolean isInEnd(@NotNull Entity entity) {
        return entity.getWorld().getEnvironment() == World.Environment.THE_END;
    }
}
