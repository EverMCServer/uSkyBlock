package us.talabrek.ultimateskyblock.event;

import com.google.common.util.concurrent.RateLimiter;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.PortalType;
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
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import us.talabrek.ultimateskyblock.Settings;
import us.talabrek.ultimateskyblock.handler.WorldGuardHandler;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 *
 * <p><b>Important:</b> Paper builds the vanilla end platform at {@code ServerLevel.END_SPAWN_POINT} <i>before</i>
 * the portal events are fired, and {@link PlayerPortalEvent#setTo(Location)} only changes the location the entity is
 * teleported to. The platform therefore has to be built (and repaired) by this plugin at the island's own arrival
 * point - it does not follow the rewritten destination.</p>
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

    /**
     * Arrival platforms that still have to be (re)built in the next tick. Coalesced so a burst of entities entering
     * the same island does not build the same platform dozens of times per tick.
     */
    private final Set<Location> pendingPlatformRebuilds = new LinkedHashSet<>();
    private boolean platformRebuildScheduled;

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

    /**
     * Rewrites the destination of players entering the end from their overworld island.
     *
     * <p>Players leaving the end are not handled here: Paper fires {@link org.bukkit.event.player.PlayerRespawnEvent}
     * (reason {@code END_PORTAL}) instead of {@code PlayerPortalEvent} for them, and the existing respawn handling in
     * {@link PlayerEvents#onPlayerRespawn(org.bukkit.event.player.PlayerRespawnEvent)} sends them back to their island
     * home.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (!Settings.end_enabled || event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            return;
        }
        World fromWorld = event.getFrom().getWorld();
        if (fromWorld == null || !plugin.getWorldManager().isSkyWorld(fromWorld)) {
            return; // Only portals leaving the overworld island are handled here.
        }
        @Nullable World endWorld = plugin.getWorldManager().getEndWorld();
        if (endWorld == null) {
            return;
        }

        Player player = event.getPlayer();
        @Nullable String islandName = WorldGuardHandler.getIslandNameAt(event.getFrom());
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

        @Nullable Location to = getEndArrivalLocation(endWorld, islandInfo);
        if (to == null) {
            event.setCancelled(true);
            player.sendMessage(tr("\u00a7cThis end portal is not linked to an island."));
            return;
        }
        event.setTo(to);
        // The platform rebuild itself is scheduled in onPlayerPortalMonitor so a portal that is cancelled later
        // (e.g. by the visitor protection at MONITOR) does not rebuild another island's platform.
        // TODO: award the "enter_end" challenge here (destination validated, player about to enter) once the
        //       challenge semantics are finalised - intentionally disabled for now.
    }

    /**
     * Schedules the arrival platform rebuild after all portal validations have run.
     *
     * <p>{@link ExploitEvents} cancels "using another island's portal" at {@link EventPriority#MONITOR}; running
     * after it (same priority, registered later) makes sure a cancelled teleport does not touch the target island's
     * platform.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerPortalMonitor(PlayerPortalEvent event) {
        if (!Settings.end_enabled || event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            return;
        }
        World fromWorld = event.getFrom().getWorld();
        if (fromWorld == null || !plugin.getWorldManager().isSkyWorld(fromWorld)) {
            return;
        }
        @Nullable World endWorld = plugin.getWorldManager().getEndWorld();
        if (endWorld == null) {
            return;
        }
        IslandInfo islandInfo = plugin.getIslandInfo(WorldGuardHandler.getIslandNameAt(event.getFrom()));
        if (islandInfo == null) {
            return;
        }
        @Nullable Location arrival = getEndArrivalLocation(endWorld, islandInfo);
        if (arrival != null) {
            ensureArrivalPlatformLater(arrival);
        }
    }

    /**
     * Rewrites the destination of non-player entities travelling between the overworld and the end.
     *
     * <p>Overworld -> end: entities arrive one block above the obsidian platform (vanilla offset) so the platform
     * never has to be entered from inside. End -> overworld: entities are sent to the island's configured return
     * point instead of the world spawn.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (!Settings.end_enabled || event.getPortalType() != PortalType.ENDER) {
            return;
        }
        World fromWorld = event.getFrom().getWorld();
        if (fromWorld == null) {
            return;
        }

        if (plugin.getWorldManager().isSkyEnd(fromWorld)) {
            // Returning from the island's end region: use the island's return point (default X, 64, Z).
            IslandInfo islandInfo = plugin.getIslandInfo(WorldGuardHandler.getIslandNameAt(event.getFrom()));
            if (islandInfo == null) {
                return; // Not inside an island region (e.g. the shared main city) - leave vanilla behaviour.
            }
            @Nullable Location returnPoint = islandInfo.getReturnPoint();
            if (returnPoint != null) {
                event.setTo(returnPoint);
            }
            return;
        }
        if (!plugin.getWorldManager().isSkyWorld(fromWorld)) {
            return; // Only portals leaving the overworld island are handled here.
        }

        @Nullable World endWorld = plugin.getWorldManager().getEndWorld();
        if (endWorld == null) {
            return;
        }
        IslandInfo islandInfo = plugin.getIslandInfo(WorldGuardHandler.getIslandNameAt(event.getFrom()));
        if (islandInfo == null) {
            // No island could be resolved - send the entity to the shared end fallback point.
            event.setTo(getEndEntityFallbackLocation(endWorld));
            return;
        }
        @Nullable Location to = getEndEntityLocation(endWorld, islandInfo);
        if (to == null) {
            return;
        }
        event.setTo(to);
        @Nullable Location arrival = getEndArrivalLocation(endWorld, islandInfo);
        if (arrival != null) {
            ensureArrivalPlatformLater(arrival);
        }
    }

    /**
     * The end world has no dragon fight, but the world's {@link org.bukkit.boss.DragonBattle} still owns a boss bar
     * and re-adds it to players entering the dimension. Clear it immediately when a player arrives; the periodic
     * cleanup in {@code WorldManager.clearDragonBossBar(World)} keeps it hidden.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (!Settings.end_enabled) {
            return;
        }
        World world = event.getPlayer().getWorld();
        if (plugin.getWorldManager().isSkyEnd(world)) {
            plugin.getWorldManager().clearDragonBossBar(world);
        }
    }

    /**
     * Blocks the ender dragon spawn in the island end world. The world has no dragon fight; letting the fight spawn
     * a dragon only creates a boss bar and a mob that would have to be removed again.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEnderDragonSpawn(EntitySpawnEvent event) {
        if (!Settings.end_enabled || event.getEntityType() != EntityType.ENDER_DRAGON) {
            return;
        }
        if (plugin.getWorldManager().isSkyEnd(event.getLocation().getWorld())) {
            event.setCancelled(true);
        }
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
     * Builds (or repairs) the arrival platform at the given location.
     *
     * <p>Paper builds the vanilla platform at the vanilla end spawn point <i>before</i> the portal event is fired, so
     * the platform does not follow a rewritten {@code setTo} destination. This method is what actually creates the
     * per island platform. Blocks that have to be removed are broken the same way vanilla does it (dropped as if
     * mined, including container contents), so the behaviour matches the vanilla platform regeneration.</p>
     *
     * @param location Center of the arrival platform (the location players are teleported to).
     */
    public static void buildArrivalPlatform(@NotNull Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        int centerX = location.getBlockX();
        int centerY = location.getBlockY();
        int centerZ = location.getBlockZ();
        for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
            for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                Block platform = world.getBlockAt(centerX + x, centerY - 1, centerZ + z);
                if (platform.getType() != Material.OBSIDIAN) {
                    breakAndDrop(platform);
                    platform.setType(Material.OBSIDIAN);
                }
                for (int y = 0; y < PLATFORM_CLEARANCE; y++) {
                    breakAndDrop(world.getBlockAt(centerX + x, centerY + y, centerZ + z));
                }
            }
        }
    }

    /**
     * Removes a block and drops it the same way vanilla's platform regeneration does (as if it was mined by a player,
     * which also drops the contents of containers).
     *
     * @param block Block to remove.
     */
    private static void breakAndDrop(@NotNull Block block) {
        if (block.getType().isAir()) {
            return;
        }
        if (block.isLiquid()) {
            block.setType(Material.AIR);
            return;
        }
        block.breakNaturally();
        if (!block.getType().isAir()) {
            block.setType(Material.AIR);
        }
    }

    /**
     * Gets the location players are teleported to when entering the end through an island portal.
     *
     * <p>The location is centered on the island's block (vanilla spawns players centered on the platform) and uses
     * {@code end.height} as the feet level; the obsidian platform is built at {@code end.height - 1}.</p>
     *
     * @param endWorld End world.
     * @param island   Island to get the arrival location for, may be null.
     * @return Arrival location, or null when the island is unknown.
     */
    @Nullable
    public static Location getEndArrivalLocation(@NotNull World endWorld, @Nullable IslandInfo island) {
        if (island == null) {
            return null;
        }
        Location islandLocation = island.getIslandLocation();
        if (islandLocation == null) {
            return null;
        }
        // Yaw 90 is west, matching the vanilla arrival direction.
        return new Location(endWorld, islandLocation.getBlockX() + 0.5, Settings.end_height,
            islandLocation.getBlockZ() + 0.5, 90.0f, 0.0f);
    }

    /**
     * Gets the location non-player entities are teleported to when entering the end through an island portal.
     *
     * <p>Vanilla offsets the entity arrival point by one block upwards compared to the player arrival point: the
     * player stands on the platform, other entities are placed one block above the player's feet.</p>
     *
     * @param endWorld End world.
     * @param island   Island to get the arrival location for, may be null.
     * @return Arrival location, or null when the island is unknown.
     */
    @Nullable
    public static Location getEndEntityLocation(@NotNull World endWorld, @Nullable IslandInfo island) {
        if (island == null) {
            return null;
        }
        Location islandLocation = island.getIslandLocation();
        if (islandLocation == null) {
            return null;
        }
        return new Location(endWorld, islandLocation.getBlockX() + 0.5, Settings.end_height + 1.0,
            islandLocation.getBlockZ() + 0.5, 90.0f, 0.0f);
    }

    /**
     * Gets the fallback location in the end world, used for players when no island can be resolved for a portal. It is
     * the world spawn / shared end "main city" around the origin (config {@code end.fallback.x/z}); the plugin places
     * no blocks there.
     *
     * @param endWorld End world.
     * @return Fallback location.
     */
    @NotNull
    public static Location getEndFallbackLocation(@NotNull World endWorld) {
        return new Location(endWorld, Settings.end_fallbackX + 0.5, Settings.end_height, Settings.end_fallbackZ + 0.5);
    }

    /**
     * Gets the fallback location in the end world for non-player entities. Entities are one block above the player
     * fallback point, matching the vanilla offset.
     *
     * @param endWorld End world.
     * @return Fallback location.
     */
    @NotNull
    public static Location getEndEntityFallbackLocation(@NotNull World endWorld) {
        return new Location(endWorld, Settings.end_fallbackX + 0.5, Settings.end_height + 1.0,
            Settings.end_fallbackZ + 0.5);
    }

    /**
     * Makes sure the arrival platform exists right after the teleport has been processed. Building it immediately
     * would force a synchronous chunk load; the teleport itself loads the destination chunk anyway. All arrivals in
     * the same tick are coalesced into a single task, and the platform is rebuilt on every arrival (like vanilla),
     * even when no player is nearby.
     *
     * @param destination Destination of the teleport (player arrival point, i.e. the platform center).
     */
    private void ensureArrivalPlatformLater(@NotNull Location destination) {
        World world = destination.getWorld();
        if (world == null) {
            return;
        }
        Location key = new Location(world, destination.getBlockX(), destination.getBlockY(), destination.getBlockZ());
        synchronized (pendingPlatformRebuilds) {
            pendingPlatformRebuilds.add(key);
            if (platformRebuildScheduled) {
                return;
            }
            platformRebuildScheduled = true;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            List<Location> rebuilds;
            synchronized (pendingPlatformRebuilds) {
                rebuilds = new ArrayList<>(pendingPlatformRebuilds);
                pendingPlatformRebuilds.clear();
                platformRebuildScheduled = false;
            }
            for (Location rebuild : rebuilds) {
                World targetWorld = rebuild.getWorld();
                if (targetWorld == null
                    || !targetWorld.isChunkLoaded(rebuild.getBlockX() >> 4, rebuild.getBlockZ() >> 4)) {
                    continue;
                }
                buildArrivalPlatform(rebuild);
            }
        });
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
