package us.talabrek.ultimateskyblock.event;

import com.google.common.util.concurrent.RateLimiter;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import io.papermc.paper.event.entity.EntityPortalReadyEvent;
import org.bukkit.event.entity.EntityPortalEvent;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * How many ticks a portal entry is remembered.
     *
     * <p>An end portal teleports instantly (its transition time is {@code 0}), so the portal block an entity uses is
     * entered in the same tick the entity is teleported: non-player entities handle portals at the end of their own
     * tick, right after the block collision check that fires {@link EntityPortalEnterEvent}. A small margin covers odd
     * tick orders (e.g. passengers, or an entity that keeps standing in the portal).</p>
     */
    private static final int PORTAL_ENTRY_TTL_TICKS = 5;

    private final uSkyBlock plugin;
    private final RateLimiter rateLimiter = RateLimiter.create(1);

    /**
     * Arrival platforms that still have to be (re)built in the next tick. Coalesced so a burst of entities entering
     * the same island does not build the same platform dozens of times per tick.
     */
    private final Set<Location> pendingPlatformRebuilds = new LinkedHashSet<>();
    private boolean platformRebuildScheduled;

    /**
     * End portal blocks entities really entered, per entity, see {@link #onEntityPortalEnter(EntityPortalEnterEvent)}.
     *
     * <p>This is the reliable counterpart of {@link EntityPortalEvent}: that event describes the <i>teleport</i>
     * (source location, destination, portal type) and carries no information at all about the portal block that
     * caused it - and both the overworld end portal and the return portal built in the end report the same
     * {@link PortalType#ENDER}. The portal <i>enter</i> event on the other hand is fired by the portal block itself and
     * includes the block position, so it tells us which world and which block actually "caught" the entity.</p>
     */
    private final Map<UUID, PortalEntry> endPortalEntries = new ConcurrentHashMap<>();

    /**
     * Tick the expired {@link #endPortalEntries} were last purged in, so the purge runs at most once per tick.
     */
    private int lastEntryPurgeTick = -1;

    /**
     * Maximum number of end portal events the diagnostic logs before it goes quiet.
     *
     * <p>TODO(diagnostic): remove together with {@link #debugPortalEvent(String, EntityPortalEvent, String)};
     * the cap keeps a busy entity contraption from flooding the server log.</p>
     */
    private static final int DEBUG_PORTAL_EVENT_LIMIT = 500;

    /**
     * Number of end portal events logged by the diagnostic so far.
     */
    private int debugPortalEventCount;

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
     *
     * <p>Both directions are decided by the world the entity is in. That distinction alone is not enough though: an
     * entity keeps the portal state of the portal it came through when it changes dimension, so the destination
     * dimension can evaluate that portal again for an entity that has just arrived - without the entity standing in
     * any portal block. Which portal the entity really used is tracked from {@link EntityPortalEnterEvent}, see
     * {@link #handleEntityLeavingEnd(EntityPortalEvent, Location, World)}.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        // TODO(diagnostic): remove the debug calls together with the diagnostic section below.
        debugPortalEvent("HIGHEST", event, "-");
        if (!Settings.end_enabled || event.getPortalType() != PortalType.ENDER) {
            return;
        }
        Location from = event.getFrom();
        World fromWorld = from.getWorld();
        if (fromWorld == null) {
            return;
        }

        if (plugin.getWorldManager().isSkyEnd(fromWorld)) {
            handleEntityLeavingEnd(event, from, fromWorld);
            return;
        }
        if (!plugin.getWorldManager().isSkyWorld(fromWorld)) {
            return; // Only portals leaving the overworld island are handled here.
        }
        handleEntityEnteringEnd(event, from, fromWorld);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // TODO(diagnostic): temporary instrumentation for the "entity entering the end ends up at the island return point"
    //                   issue. Logs every end portal event at three priorities plus the portal enter/ready events, so we
    //                   can see whether something (e.g. Multiverse-NetherPortals) cancels or redirects it before the
    //                   plugin runs. Remove this whole section (and its calls) once the issue is understood.
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Logs every entity portal event as it enters the event pipeline, before any plugin touched it.
     *
     * @param event Event to log.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityPortalRaw(EntityPortalEvent event) {
        debugPortalEvent("LOWEST", event, "-");
    }

    /**
     * Logs the state of every entity portal event after all plugins (including this one) are done with it.
     *
     * @param event Event to log.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityPortalFinal(EntityPortalEvent event) {
        debugPortalEvent("MONITOR", event, "-");
    }

    /**
     * Logs every Paper {@code EntityPortalReadyEvent} (Multiverse-NetherPortals redirects entities through it).
     *
     * @param event Event to log.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityPortalReady(EntityPortalReadyEvent event) {
        if (!takeDebugSlot()) {
            return;
        }
        debugLog("READY entity=" + event.getEntityType() + "/" + shortId(event.getEntity())
            + " portal=" + event.getPortalType()
            + " target=" + (event.getTargetWorld() != null ? event.getTargetWorld().getName() : "null")
            + " cancelled=" + event.isCancelled());
    }

    /**
     * Logs an entity portal event with everything the plugin can know about it.
     *
     * @param stage Stage of the event pipeline the line is logged at.
     * @param event Event to log.
     * @param note  Short description of what the plugin is going to do.
     */
    private void debugPortalEvent(@NotNull String stage, @NotNull EntityPortalEvent event, @NotNull String note) {
        if (!takeDebugSlot()) {
            return;
        }
        @Nullable Location from = event.getFrom();
        @Nullable World fromWorld = from != null ? from.getWorld() : null;
        @Nullable Location recent = fromWorld != null ? getRecentEndPortalBlock(event.getEntity(), fromWorld) : null;
        debugLog(stage
            + " entity=" + event.getEntityType() + "/" + shortId(event.getEntity())
            + " portal=" + event.getPortalType()
            + " cancelled=" + event.isCancelled()
            + " from=" + describe(from)
            + " block=" + (from != null ? from.getBlock().getType() : "?")
            + " to=" + describe(event.getTo())
            + " fromKind=" + (fromWorld != null ? worldKind(fromWorld) : "?")
            + " recentEntry=" + describe(recent)
            + " | " + note);
    }

    /**
     * Takes a diagnostic log slot, so a busy contraption cannot flood the server log.
     *
     * @return True when a line may be logged.
     */
    private boolean takeDebugSlot() {
        if (debugPortalEventCount > DEBUG_PORTAL_EVENT_LIMIT) {
            return false;
        }
        debugPortalEventCount++;
        if (debugPortalEventCount == DEBUG_PORTAL_EVENT_LIMIT) {
            plugin.getLogger().info("[end-debug] " + DEBUG_PORTAL_EVENT_LIMIT
                + " diagnostic lines logged, further end portal events are suppressed.");
        }
        return debugPortalEventCount <= DEBUG_PORTAL_EVENT_LIMIT;
    }

    /**
     * Writes a diagnostic line.
     *
     * @param message Message to log.
     */
    private void debugLog(@NotNull String message) {
        plugin.getLogger().info("[end-debug] #" + debugPortalEventCount + " " + message);
    }

    /**
     * Formats the short id of an entity for the diagnostic log.
     *
     * @param entity Entity to format.
     * @return First 8 characters of the entity UUID.
     */
    @NotNull
    private static String shortId(@NotNull Entity entity) {
        return entity.getUniqueId().toString().substring(0, 8);
    }

    /**
     * Formats a location for the diagnostic log.
     *
     * @param location Location to format, may be null.
     * @return {@code world@(x,y,z)}, or {@code null}.
     */
    @NotNull
    private static String describe(@Nullable Location location) {
        if (location == null) {
            return "null";
        }
        World world = location.getWorld();
        String worldName = world != null ? world.getName() : "?";
        return worldName + "@(" + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ() + ")";
    }

    /**
     * Classifies a world for the diagnostic log.
     *
     * @param world World to classify.
     * @return {@code end}, {@code overworld} or {@code other}.
     */
    @NotNull
    private String worldKind(@NotNull World world) {
        if (plugin.getWorldManager().isSkyEnd(world)) {
            return "end";
        }
        return plugin.getWorldManager().isSkyWorld(world) ? "overworld" : "other";
    }

    /**
     * Sends an entity that travels from the overworld island into the end to that island's own arrival point.
     *
     * @param event     Entity portal event to rewrite.
     * @param from      Entity location before the teleport (inside the portal on the overworld island).
     * @param fromWorld World the portal event happens in.
     */
    private void handleEntityEnteringEnd(@NotNull EntityPortalEvent event, @NotNull Location from,
                                         @NotNull World fromWorld) {
        @Nullable World endWorld = plugin.getWorldManager().getEndWorld();
        if (endWorld == null) {
            return;
        }
        // Resolve the island from the portal block itself when it is known, not from where the entity happens to stand.
        @Nullable Location portalBlock = getRecentEndPortalBlock(event.getEntity(), fromWorld);
        Location reference = portalBlock != null ? portalBlock : from;
        IslandInfo islandInfo = plugin.getIslandInfo(WorldGuardHandler.getIslandNameAt(reference));
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
     * Remembers which end portal block an entity actually walked into.
     *
     * <p>{@link EntityPortalEvent} cannot answer that: it describes the teleport and knows nothing about the portal
     * block that caused it, and the overworld end portal and the return portal built in the end are the very same
     * block (both report {@link PortalType#ENDER}). This event is fired from the portal block's own entity collision
     * check, so it also carries the block position and is independent of where the entity's reference point happens to
     * be - falling blocks, boats, passengers and mobs with unusual hitboxes are recognised exactly like players.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityPortalEnter(EntityPortalEnterEvent event) {
        // TODO(diagnostic): remove this log together with the diagnostic section below.
        if (takeDebugSlot()) {
            debugLog("ENTER entity=" + event.getEntityType() + "/" + shortId(event.getEntity())
                + " portal=" + event.getPortalType()
                + " cancelled=" + event.isCancelled()
                + " world=" + describe(event.getLocation()));
        }
        if (event.isCancelled() || !Settings.end_enabled || event.getPortalType() != PortalType.ENDER) {
            return;
        }
        Location portalBlock = event.getLocation();
        World world = portalBlock.getWorld();
        if (world == null) {
            return;
        }
        if (!plugin.getWorldManager().isSkyWorld(world) && !plugin.getWorldManager().isSkyEnd(world)) {
            return;
        }
        int now = Bukkit.getCurrentTick();
        Location block = new Location(world, portalBlock.getBlockX(), portalBlock.getBlockY(),
            portalBlock.getBlockZ());
        endPortalEntries.put(event.getEntity().getUniqueId(), new PortalEntry(world.getName(), block, now));
        // Drop expired entries, but at most once per tick: this handler fires every tick for every entity that stays
        // inside a portal, so purging per event would be O(entities in portals^2).
        if (lastEntryPurgeTick != now) {
            lastEntryPurgeTick = now;
            endPortalEntries.values().removeIf(entry -> now - entry.tick() > PORTAL_ENTRY_TTL_TICKS);
        }
    }

    /**
     * Sends an entity that leaves the island's end region through a return portal back to the island's return point.
     *
     * <p><b>Only a portal the entity really entered counts as "leaving the end".</b> Two things make this necessary:
     * the overworld end portal and the return portal built in the end are the same block, and an entity keeps the
     * portal it went through after changing dimension, so the destination dimension evaluates that portal again. A
     * portal event is therefore also fired for an entity that has just arrived in the end and is standing on the
     * obsidian arrival platform, without it being inside any portal. Rewriting such an event to the return point would
     * teleport the entity straight back to the overworld (that was the "entity entering the end ends up at the island
     * return point" bug), so it is cancelled instead: a portal event that was not caused by a portal block must not
     * move the entity at all.</p>
     *
     * <p>The check uses the recorded portal entry of this entity in the same world (see
     * {@link #onEntityPortalEnter(EntityPortalEnterEvent)}); the block below/at/above the entity is only a fallback for
     * the rare case where the entry event was missed.</p>
     */
    private void handleEntityLeavingEnd(@NotNull EntityPortalEvent event, @NotNull Location from,
                                        @NotNull World fromWorld) {
        @Nullable Location portalBlock = getRecentEndPortalBlock(event.getEntity(), fromWorld);
        if (portalBlock == null && !isInsideEndPortalBlock(from)) {
            event.setCancelled(true);
            return;
        }
        // Resolve the island from the portal block itself when it is known, not from where the entity happens to stand.
        Location reference = portalBlock != null ? portalBlock : from;
        IslandInfo islandInfo = plugin.getIslandInfo(WorldGuardHandler.getIslandNameAt(reference));
        if (islandInfo == null) {
            return; // Not inside an island region (e.g. the shared main city) - leave vanilla behaviour.
        }
        @Nullable Location returnPoint = islandInfo.getReturnPoint();
        if (returnPoint != null) {
            event.setTo(returnPoint);
        }
    }

    /**
     * Gets the end portal block the given entity entered recently in the given world.
     *
     * @param entity Entity to look up.
     * @param world  World the portal event is happening in; entries from another world are ignored, which is what
     *               separates "the entity just used this portal" from "the entity still carries the portal it used in
     *               the other dimension".
     * @return The portal block, or null when the entity did not enter an end portal block in that world recently.
     */
    @Nullable
    private Location getRecentEndPortalBlock(@NotNull Entity entity, @NotNull World world) {
        PortalEntry entry = endPortalEntries.get(entity.getUniqueId());
        if (entry == null || !entry.worldName().equals(world.getName())) {
            return null;
        }
        if (Bukkit.getCurrentTick() - entry.tick() > PORTAL_ENTRY_TTL_TICKS) {
            return null;
        }
        return entry.block();
    }

    /**
     * Checks whether the given location is inside an {@code END_PORTAL} block.
     *
     * <p>Fallback for {@link #getRecentEndPortalBlock(Entity, World)}: whether the entity's reference position is in
     * the portal block depends on the entity's hitbox, so this is only used when no portal entry was recorded.</p>
     *
     * <p>The blocks above and below the entity are checked as well, because entities of different sizes and sizes of
     * their bounding boxes can have their reference position in the block above or below the portal column.</p>
     *
     * @param location Entity location to inspect.
     * @return True when the entity stands in an end portal block.
     */
    private static boolean isInsideEndPortalBlock(@NotNull Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        for (int offset = -1; offset <= 1; offset++) {
            if (world.getBlockAt(x, y + offset, z).getType() == Material.END_PORTAL) {
                return true;
            }
        }
        return false;
    }

    /**
     * An end portal block an entity entered, with the tick it was entered.
     *
     * @param worldName Name of the world the portal block is in.
     * @param block     Portal block location.
     * @param tick      Tick the entry was recorded in, used to expire stale entries.
     */
    private record PortalEntry(@NotNull String worldName, @NotNull Location block, int tick) {
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
