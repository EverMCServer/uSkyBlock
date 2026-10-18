package us.talabrek.ultimateskyblock.bootstrap;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.PluginConfig;
import us.talabrek.ultimateskyblock.Settings;
import us.talabrek.ultimateskyblock.chat.ChatEvents;
import us.talabrek.ultimateskyblock.event.*;
import us.talabrek.ultimateskyblock.gui.GuiListener;
import us.talabrek.ultimateskyblock.signs.SignEvents;
import us.talabrek.ultimateskyblock.command.InviteHandler;
import us.talabrek.ultimateskyblock.uuid.PlayerDB;

@Singleton
public class Listeners {

    /**
     * TEMPORARY DEBUG SWITCH - leave at {@code true} unless you are debugging the end portal.
     *
     * <p>When {@code false} the {@link EndPortalEvents} listener is not registered at all: the plugin stops rewriting
     * the destination of players and entities that use an island end portal, stops rebuilding the arrival platform
     * and stops the ender pearl region check. End portals then behave exactly the way Paper and
     * Multiverse-NetherPortals leave them.</p>
     *
     * <p>This exists to tell a "does not work on the server but works in single player" problem apart from this
     * plugin's own end portal handling (e.g. sand duplication). Do not ship a build with this set to {@code false}:
     * without the redirection every player and entity that enters an island end portal ends up at the shared end
     * fallback instead of the island's own arrival platform.</p>
     */
    private static final boolean END_PORTAL_REDIRECTION = false;

    /**
     * TEMPORARY DEBUG SWITCH - end portal diagnostics.
     *
     * <p>Registers {@link EndPortalDebugEvents}, which writes the end portal pipeline of falling blocks and dropped
     * items (portal block touched, portal event at lowest/highest priority, every cross world teleport) to the log.
     * Only useful together with {@link #END_PORTAL_REDIRECTION} being {@code false}. Turn it off (and delete the
     * class) once the end portal investigation is over.</p>
     */
    private static final boolean END_PORTAL_DEBUG = true;

    private final PluginConfig config;

    private final GuiListener guiListener;
    private final InternalEvents internalEvents;
    private final PlayerEvents playerEvents;
    private final ProgressEvents progressEvents;
    private final AltarEvents alterEvents;
    private final MenuEvents menuEvents;
    private final ExploitEvents exploitEvents;
    private final WitherTagEvents witherTagEvents;
    private final GriefEvents griefEvents;
    private final ItemDropEvents itemDropEvents;
    private final IslandBorderEvent islandBorderEvent;
    private final SpawnEvents spawnEvents;
    private final ToxicEvents toxicEvents;
    private final SulfurEvents sulfurEvents;
    private final WorldGuardEvents worldGuardEvents;
    private final NetherTerraFormEvents netherTerraFormEvents;
    private final EndPortalEvents endPortalEvents;
    private final EndPortalDebugEvents endPortalDebugEvents;
    private final ToolMenuEvents toolMenuEvents;
    private final SignEvents signEvents;
    private final ChatEvents chatEvents;
    private final InviteHandler inviteHandler;
    private final PlayerDB playerDB;

    @Inject
    public Listeners(
        @NotNull PluginConfig config,
        @NotNull GuiListener guiListener,
        @NotNull InternalEvents internalEvents,
        @NotNull PlayerEvents playerEvents,
        @NotNull ProgressEvents progressEvents,
        @NotNull AltarEvents alterEvents,
        @NotNull MenuEvents menuEvents,
        @NotNull ExploitEvents exploitEvents,
        @NotNull WitherTagEvents witherTagEvents,
        @NotNull GriefEvents griefEvents,
        @NotNull ItemDropEvents itemDropEvents,
        @NotNull IslandBorderEvent islandBorderEvent,
        @NotNull SpawnEvents spawnEvents,
        @NotNull ToxicEvents toxicEvents,
        @NotNull SulfurEvents sulfurEvents,
        @NotNull WorldGuardEvents worldGuardEvents,
        @NotNull NetherTerraFormEvents netherTerraFormEvents,
        @NotNull EndPortalEvents endPortalEvents,
        @NotNull EndPortalDebugEvents endPortalDebugEvents,
        @NotNull ToolMenuEvents toolMenuEvents,
        @NotNull SignEvents signEvents,
        @NotNull ChatEvents chatEvents,
        @NotNull InviteHandler inviteHandler,
        @NotNull PlayerDB playerDB
    ) {
        this.config = config;
        this.guiListener = guiListener;
        this.internalEvents = internalEvents;
        this.playerEvents = playerEvents;
        this.progressEvents = progressEvents;
        this.alterEvents = alterEvents;
        this.menuEvents = menuEvents;
        this.exploitEvents = exploitEvents;
        this.witherTagEvents = witherTagEvents;
        this.griefEvents = griefEvents;
        this.itemDropEvents = itemDropEvents;
        this.islandBorderEvent = islandBorderEvent;
        this.spawnEvents = spawnEvents;
        this.toxicEvents = toxicEvents;
        this.sulfurEvents = sulfurEvents;
        this.worldGuardEvents = worldGuardEvents;
        this.netherTerraFormEvents = netherTerraFormEvents;
        this.endPortalEvents = endPortalEvents;
        this.endPortalDebugEvents = endPortalDebugEvents;
        this.toolMenuEvents = toolMenuEvents;
        this.signEvents = signEvents;
        this.chatEvents = chatEvents;
        this.inviteHandler = inviteHandler;
        this.playerDB = playerDB;
    }

    public void registerListeners(Plugin plugin) {
        PluginManager manager = plugin.getServer().getPluginManager();

        manager.registerEvents(internalEvents, plugin);
        manager.registerEvents(playerEvents, plugin);
        manager.registerEvents(alterEvents, plugin);
        manager.registerEvents(menuEvents, plugin);
        manager.registerEvents(guiListener, plugin);
        manager.registerEvents(exploitEvents, plugin);
        manager.registerEvents(witherTagEvents, plugin);
        manager.registerEvents(chatEvents, plugin);
        manager.registerEvents(inviteHandler, plugin);
        manager.registerEvents(playerDB, plugin);

        manager.registerEvents(islandBorderEvent, plugin);

        // TODO minoneer 06.02.2025: Move this logic. Either into the appropriate listener, or into submodules if we don't want all features active (e.g., the nether)
        if (config.getYamlConfig().getBoolean("options.protection.enabled", true)) {
            manager.registerEvents(griefEvents, plugin);
            if (config.getYamlConfig().getBoolean("options.protection.item-drops", true)) {
                manager.registerEvents(itemDropEvents, plugin);
            }
        }
        if (config.getYamlConfig().getBoolean("options.island.spawn-limits.enabled", true)) {
            manager.registerEvents(spawnEvents, plugin);
        }
        if (config.getYamlConfig().getBoolean("options.protection.visitors.block-banned-entry", true)) {
            manager.registerEvents(worldGuardEvents, plugin);
        }
        if (Settings.nether_enabled) {
            manager.registerEvents(netherTerraFormEvents, plugin);
        }
        if (Settings.end_enabled && END_PORTAL_REDIRECTION) {
            manager.registerEvents(endPortalEvents, plugin);
        } else if (Settings.end_enabled) {
            // Loud on purpose: it must be obvious from the log which build is running and that the end portal
            // redirection is switched off (see END_PORTAL_REDIRECTION).
            plugin.getLogger().warning("End portal redirection is DISABLED (debug switch in Listeners) - "
                + "end portals are NOT rewritten to the island's own arrival platform");
        }
        if (END_PORTAL_DEBUG) {
            manager.registerEvents(endPortalDebugEvents, plugin);
            plugin.getLogger().warning("End portal debug logging is ENABLED (EndPortalDebugEvents) - "
                + "expect [end-debug] lines for falling blocks and dropped items");
        }
        if (config.getYamlConfig().getBoolean("tool-menu.enabled", true)) {
            manager.registerEvents(toolMenuEvents, plugin);
        }
        if (config.getYamlConfig().getBoolean("signs.enabled", true)) {
            manager.registerEvents(signEvents, plugin);
        }

        manager.registerEvents(toxicEvents, plugin);
        manager.registerEvents(sulfurEvents, plugin);
        manager.registerEvents(progressEvents, plugin);
    }

    public void unregisterListeners(Plugin plugin) {
        HandlerList.unregisterAll(plugin);
    }
}
