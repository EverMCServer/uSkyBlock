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
     * World an entity was in when its portal event was first seen, per entity.
     *
     * <p>The direction of a portal event has to be decided from the world the entity comes from, but
     * {@link EntityPortalEvent#getFrom()} cannot be trusted for that: other plugins mutate the event while it is being
     * dispatched. Multiverse-NetherPortals (it auto-links every {@code <world>_the_end} world, which matches this
     * plugin's end world) passes {@code event.getFrom()} into its own location calculation, which rewrites the world
     * of that very {@link Location} instance in place, and also replaces the destination with a location in the linked
     * world. By the time a later handler looks at the event, {@code getFrom()} can therefore already point at the end
     * world - which made an entity that is <i>entering</i> the end look like one that is <i>leaving</i> it, and sent it
     * to the island return point. The snapshot taken at {@link EventPriority#LOWEST} (before any plugin runs) is used
     * instead; see {@link #getPortalWorld(EntityPortalEvent)}.</p>
     */
    private final Map<UUID, FromSnapshot> portalFromSnapshots = new ConcurrentHashMap<>();

    /**
     * Tick the expired {@link #portalFromSnapshots} were last purged in, so the purge runs at most once per tick.
     */
    private int lastSnapshotPurgeTick = -1;

    /**
     * Tick the expired {@link #endPortalEntries} were last purged in, so the purge runs at most once per tick.
     */
    private int lastEntryPurgeTick = -1;

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
     *
     * <p>The handler runs for cancelled events as well (and lifts the cancellation once the destination is known):
     * Multiverse-NetherPortals cancels portals it has no linked world for (its {@code portal-auto-link-when} suffix no
     * longer matching this plugin's end world, or {@code bounceback}), which would otherwise disable every island end
     * portal - players and contraptions alike. The island end portals belong to this plugin, so its destination wins.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
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
        // Destination known: take the portal over even if another plugin cancelled it (see the class comment above).
        event.setCancelled(false);
        event.setTo(to);
        // The platform rebuild and the "enter_end" challenge are handled in onPlayerPortalMonitor: this handler runs
        // before other plugins can still cancel the portal (e.g. the visitor protection at MONITOR), and awarding a
        // challenge for a teleport that never happens is exactly the bug the old implementation had.
    }

    /**
     * Awards the {@code enter_end} challenge and schedules the arrival platform rebuild, after all portal validations
     * have run.
     *
     * <p>{@link ExploitEvents} cancels "using another island's portal" at {@link EventPriority#MONITOR}; running
     * after it (same priority, registered later) makes sure a cancelled teleport does not touch the target island's
     * platform.</p>
     *
     * <p>This is also the only place the {@code enter_end} challenge is awarded: the portal is no longer cancellable
     * here (the plugin's own validation, the "another island's portal" check and the dimension gate all ran before),
     * so a challenge is never granted for a teleport that does not happen - which is what the old implementation
     * did (it also completed for players without an island, portals without a link, other islands' portals and the
     * lobby's end portal).</p>
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
        @Nullable PlayerInfo playerInfo = plugin.getPlayerInfo(event.getPlayer());
        if (playerInfo != null) {
            plugin.getChallengeLogic().completeChallengeIfNotDone(playerInfo, "enter_end");
        }
    }

    /**
     * Rewrites the destination of non-player entities travelling between the overworld and the end.
     *
     * <p>Overworld -> end: entities arrive one block above the obsidian platform (vanilla offset) so the platform
     * never has to be entered from inside. End -> overworld: entities are sent to the island's configured return
     * point instead of the world spawn.</p>
     *
     * <p>Both directions are decided by the world the entity comes from, but that world is read from the snapshot
     * taken at {@link EventPriority#LOWEST} (see {@link #portalFromSnapshots}): other plugins rewrite the event while
     * it is dispatched, and {@code getFrom()} can already point at the destination world by the time this handler
     * runs. The portal the entity really entered is tracked from {@link EntityPortalEnterEvent} and used to tell a
     * genuine portal exit apart from a portal event the destination dimension fires for the portal the entity came
     * through.</p>
     *
     * <p>Cancelled events are handled as well and the cancellation is lifted once the destination is known: see
     * {@link #onPlayerPortal(PlayerPortalEvent)} for why (Multiverse-NetherPortals cancels portals it cannot route).</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityPortal(EntityPortalEvent event) {
        if (!Settings.end_enabled || event.getPortalType() != PortalType.ENDER) {
            return;
        }
        @Nullable World fromWorld = getPortalWorld(event);
        if (fromWorld == null) {
            return;
        }
        boolean leaving = plugin.getWorldManager().isSkyEnd(fromWorld);
        if (!leaving && !plugin.getWorldManager().isSkyWorld(fromWorld)) {
            return; // Not a portal of the plugin worlds.
        }
        if (event.isCancelled()) {
            // The plugin owns the island end portals, so a cancellation by another plugin (Multiverse-NetherPortals
            // cancels portals it has no linked world for) must not disable them. The destination set below is the
            // island's own arrival point / return point, which is what the island model expects anyway.
            event.setCancelled(false);
        }
        if (leaving) {
            handleEntityLeavingEnd(event, event.getFrom());
            return;
        }
        handleEntityEnteringEnd(event, event.getFrom(), fromWorld);
    }

    /**
     * Gets the world a portal event comes from.
     *
     * <p>Prefers the snapshot taken at {@link EventPriority#LOWEST}; falls back to the event itself when the snapshot
     * is missing or expired.</p>
     *
     * @param event Event to inspect.
     * @return World the entity comes from, or null when unknown.
     */
    @Nullable
    private World getPortalWorld(@NotNull EntityPortalEvent event) {
        FromSnapshot snapshot = portalFromSnapshots.get(event.getEntity().getUniqueId());
        if (snapshot != null && Bukkit.getCurrentTick() - snapshot.tick() <= PORTAL_ENTRY_TTL_TICKS) {
            @Nullable World world = Bukkit.getWorld(snapshot.worldName());
            if (world != null) {
                return world;
            }
        }
        @Nullable Location from = event.getFrom();
        return from != null ? from.getWorld() : null;
    }

    /**
     * Snapshots the world an entity portal event comes from, before any other plugin can rewrite it.
     *
     * <p>The snapshot drives the direction decision in {@link #onEntityPortal(EntityPortalEvent)}, see
     * {@link #portalFromSnapshots}.</p>
     *
     * @param event Event to snapshot.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityPortalSnapshot(EntityPortalEvent event) {
        @Nullable Location from = event.getFrom();
        @Nullable World fromWorld = from != null ? from.getWorld() : null;
        if (fromWorld == null) {
            return;
        }
        int now = Bukkit.getCurrentTick();
        portalFromSnapshots.put(event.getEntity().getUniqueId(), new FromSnapshot(fromWorld.getName(), now));
        // Drop expired snapshots at most once per tick.
        if (lastSnapshotPurgeTick != now) {
            lastSnapshotPurgeTick = now;
            portalFromSnapshots.values().removeIf(entry -> now - entry.tick() > PORTAL_ENTRY_TTL_TICKS);
        }
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
        if (plugin.getWorldManager().isSkyEnd(event.getEntity().getWorld())) {
            // The entity is already in the end: the destination dimension is re-evaluating the portal the entity came
            // through. Moving it again would teleport it away from where it just arrived.
            event.setCancelled(true);
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
        // This overwrites whatever another plugin (e.g. Multiverse-NetherPortals) put into the event: the entity has
        // to arrive on its own island's platform, not at the spawn of the linked world.
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
     * <p><b>Only a portal the entity really entered counts as "leaving the end".</b> An entity keeps the portal it
     * went through after changing dimension, so the destination dimension evaluates that portal again: a portal event
     * is therefore also fired for an entity that has just arrived in the end and is standing on the obsidian arrival
     * platform, without it being inside any portal. Rewriting such an event to the return point would teleport the
     * entity straight back to the overworld, so it is cancelled instead: a portal event that was not caused by a portal
     * block in the end must not move the entity at all.</p>
     *
     * <p>The check uses the recorded portal entry of this entity in the end world (see
     * {@link #onEntityPortalEnter(EntityPortalEnterEvent)}); the block below/at/above the entity is only a fallback for
     * the rare case where the entry event was missed.</p>
     */
    private void handleEntityLeavingEnd(@NotNull EntityPortalEvent event, @NotNull Location from) {
        @Nullable World endWorld = event.getEntity().getWorld();
        @Nullable Location portalBlock = endWorld != null ? getRecentEndPortalBlock(event.getEntity(), endWorld) : null;
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
            // Overwrites whatever another plugin put into the event (Multiverse-NetherPortals redirects it to the
            // spawn of the linked world).
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
     * World an entity portal event came from, captured at {@link EventPriority#LOWEST}.
     *
     * @param worldName Name of the world the entity came from.
     * @param tick      Tick the snapshot was taken in, used to expire stale snapshots.
     */
    private record FromSnapshot(@NotNull String worldName, int tick) {
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
