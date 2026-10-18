package us.talabrek.ultimateskyblock.event;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import us.talabrek.ultimateskyblock.uSkyBlock;

/**
 * TEMPORARY diagnostic listener for end portal debugging.
 *
 * <p>Writes the end portal pipeline of falling blocks and dropped items to the log: the portal block the entity
 * touched, the portal event at its lowest and highest priority (so a cancellation by another plugin is visible
 * together with the destination that plugin set), and every cross world teleport of that entity (so a teleport that
 * is answered by a second teleport back becomes visible as two lines).</p>
 *
 * <p>Registered from {@code bootstrap.Listeners} only while its temporary debug switch is enabled, together with the
 * {@code Listeners.END_PORTAL_REDIRECTION} switch that disables the plugin's own end portal handling. Remove this
 * class again once the end portal / sand duplication investigation is over.</p>
 */
@Singleton
public class EndPortalDebugEvents implements Listener {

    /**
     * TEMPORARY TEST SWITCH - only meaningful for the sand duplication investigation.
     *
     * <p>Paper only restores the vanilla falling block duplication when the teleport crosses the <b>vanilla</b>
     * {@code minecraft:the_end} dimension ({@code FallingBlockEntity#teleport} sets
     * {@code forceTickAfterTeleportToDuplicate} only when {@code oldDimension == Level.END || newDimension == Level.END}).
     * The island end is a <i>custom</i> dimension ({@code minecraft:world_acidisland_the_end}), so the restoration never
     * kicks in there.</p>
     *
     * <p>This switch sends every entity end portal destination to the vanilla end dimension instead, to prove that the
     * duplication starts working as soon as the destination dimension is {@code minecraft:the_end}. Turn it off again
     * after the test.</p>
     */
    private static final boolean REDIRECT_ENTITIES_TO_VANILLA_END = true;

    /**
     * Bukkit name of the world backing the vanilla {@code minecraft:the_end} dimension (see the worlds.yml of
     * Multiverse-Core on the test server).
     */
    private static final String VANILLA_END_WORLD = "world_the_end";

    private final uSkyBlock plugin;

    @Inject
    public EndPortalDebugEvents(@NotNull uSkyBlock plugin) {
        this.plugin = plugin;
    }

    /**
     * Only the entities that reproduce the problem are logged, so the log does not fill up with arrow/boat noise.
     *
     * @param entity Entity to check.
     * @return True when the entity is a falling block or a dropped item.
     */
    private static boolean interesting(@NotNull Entity entity) {
        return entity instanceof FallingBlock || entity instanceof Item;
    }

    /**
     * Formats a location for the log.
     *
     * @param location Location to format, may be null.
     * @return Short one line representation.
     */
    private static String format(@Nullable Location location) {
        if (location == null) {
            return "null";
        }
        String world = location.getWorld() != null ? location.getWorld().getName() : "?";
        return String.format("%s@%.2f,%.2f,%.2f", world, location.getX(), location.getY(), location.getZ());
    }

    private void log(@NotNull String message) {
        plugin.getLogger().info("[end-debug] " + message);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPortalEnter(@NotNull EntityPortalEnterEvent event) {
        if (!interesting(event.getEntity())) {
            return;
        }
        log("ENTER " + event.getEntity().getType() + " " + format(event.getLocation())
            + " portal=" + event.getPortalType() + " cancelled=" + event.isCancelled());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityPortalLowest(@NotNull EntityPortalEvent event) {
        if (!interesting(event.getEntity())) {
            return;
        }
        log("PORTAL@LOWEST " + event.getEntity().getType() + " " + format(event.getFrom())
            + " -> " + format(event.getTo()) + " type=" + event.getPortalType()
            + " cancelled=" + event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityPortalMonitor(@NotNull EntityPortalEvent event) {
        if (!interesting(event.getEntity())) {
            return;
        }
        log("PORTAL@MONITOR " + event.getEntity().getType() + " " + format(event.getFrom())
            + " -> " + format(event.getTo()) + " type=" + event.getPortalType()
            + " cancelled=" + event.isCancelled());
        redirectToVanillaEnd(event);
    }

    /**
     * TEST ONLY: sends the entity to the vanilla end dimension, see {@link #REDIRECT_ENTITIES_TO_VANILLA_END}.
     *
     * @param event Entity portal event to rewrite.
     */
    private void redirectToVanillaEnd(@NotNull EntityPortalEvent event) {
        if (!REDIRECT_ENTITIES_TO_VANILLA_END || event.getPortalType() != org.bukkit.PortalType.ENDER) {
            return;
        }
        org.bukkit.World vanillaEnd = findVanillaEndWorld();
        if (vanillaEnd == null) {
            return;
        }
        if (event.isCancelled()) {
            // The test must not be blocked by whoever cancelled the portal; the destination below wins.
            event.setCancelled(false);
        }
        // Vanilla end spawn point, the same coordinates Paper's EndPortalBlock uses.
        Location target = new Location(vanillaEnd, 100.5, 50.0, 0.5, 90.0f, 0.0f);
        event.setTo(target);
        log("REDIRECT " + event.getEntity().getType() + " -> " + format(target)
            + " (vanilla end dimension test)");
    }

    /**
     * Finds the world backing the vanilla {@code minecraft:the_end} dimension.
     *
     * @return The vanilla end world, or null when it is not loaded.
     */
    @Nullable
    private static org.bukkit.World findVanillaEndWorld() {
        org.bukkit.World byName = org.bukkit.Bukkit.getWorld(VANILLA_END_WORLD);
        if (byName != null) {
            return byName;
        }
        for (org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
            if (world.getEnvironment() == org.bukkit.World.Environment.THE_END
                && !world.getName().contains("acidisland")) {
                return world;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityTeleport(@NotNull EntityTeleportEvent event) {
        if (!interesting(event.getEntity())) {
            return;
        }
        Location to = event.getTo();
        Location from = event.getFrom();
        if (from.getWorld() == to.getWorld()) {
            return; // Same world teleports are not what we are looking for (no dimension change).
        }
        log("TELEPORT " + event.getEntity().getType() + " " + format(from) + " -> " + format(to)
            + " cancelled=" + event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlayerPortal(@NotNull PlayerPortalEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            return;
        }
        log("PLAYER " + event.getPlayer().getName() + " " + format(event.getFrom())
            + " -> " + format(event.getTo()) + " cancelled=" + event.isCancelled());
    }
}
