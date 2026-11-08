package us.talabrek.ultimateskyblock.enddragon;

import com.google.inject.Inject;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.command.island.RequirePlayerCommand;
import us.talabrek.ultimateskyblock.gui.GuiManager;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.Map;

import static dk.lockfuglsang.minecraft.po.I18nUtil.marktr;

/**
 * {@code /is enddragon} — opens the challenge menu. Starting still requires the dragon_altar region; claiming the
 * reward is allowed anywhere by default.
 */
public class EndDragonCommand extends RequirePlayerCommand {
    private final uSkyBlock plugin;
    private final DragonFightManager manager;
    private final EndDragonConfig config;
    private final GuiManager guiManager;

    @Inject
    public EndDragonCommand(@NotNull uSkyBlock plugin, @NotNull DragonFightManager manager,
                            @NotNull EndDragonConfig config, @NotNull GuiManager guiManager) {
        super("enddragon|ed", "usb.island.enddragon", marktr("open the ender dragon challenge menu"));
        this.plugin = plugin;
        this.manager = manager;
        this.config = config;
        this.guiManager = guiManager;
    }

    @Override
    protected boolean doExecute(String alias, Player player, Map<String, Object> data, String... args) {
        if (!config.enabled() && !manager.hasPendingRewards(player)) {
            player.sendMessage("§c[末影龙] 该功能未启用。");
            return true;
        }
        guiManager.openGui(new EndDragonMenu(plugin, manager, config, guiManager), player);
        return true;
    }
}
